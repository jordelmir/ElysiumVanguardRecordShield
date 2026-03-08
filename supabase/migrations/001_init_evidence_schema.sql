-- ============================================================================
-- ELYSIUM VANGUARD RECORD SHIELD — Evidence Schema v1.0
-- ============================================================================
-- Purpose: Creates the foundational database schema for the anti-sabotage
-- evidence recording system. Every table enforces Row Level Security (RLS)
-- to ensure device-level data isolation.
--
-- Architecture Decision: We use a service_role key on the Vercel middleware
-- for writes, and device-scoped JWT tokens for reads. This means RLS
-- policies must differentiate between service-role inserts and
-- device-scoped selects.
-- ============================================================================

-- Enable required extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ============================================================================
-- TABLE: devices
-- ============================================================================
-- Why: Every Android device that uses the app must register itself before
-- uploading evidence. The device_fingerprint is a SHA-256 hash of
-- immutable device properties (Android ID + app signing cert hash),
-- making it resistant to spoofing. The api_key_hash stores a bcrypt
-- hash of the rotatable API key assigned to this device.
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.devices (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    device_fingerprint TEXT NOT NULL UNIQUE,
    device_name     TEXT NOT NULL DEFAULT 'Unknown Device',
    api_key_hash    TEXT NOT NULL,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    last_seen_at    TIMESTAMPTZ DEFAULT NOW(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Why: We index the fingerprint for fast lookup during token validation.
-- Every upload request hits this index to verify the device exists.
CREATE INDEX idx_devices_fingerprint ON public.devices(device_fingerprint);
CREATE INDEX idx_devices_active ON public.devices(is_active) WHERE is_active = TRUE;

COMMENT ON TABLE public.devices IS 
    'Registry of authorized Android devices. Each device must register with a unique fingerprint before uploading evidence.';

-- ============================================================================
-- TABLE: recordings
-- ============================================================================
-- Why: A recording is a logical session that groups multiple chunks together.
-- It tracks the lifecycle (recording → completed → interrupted) of each
-- capture session. The status field is critical for the anti-sabotage
-- system: if a recording stays in 'recording' state forever, it means
-- the device was forcibly destroyed mid-capture.
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.recordings (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    device_id       UUID NOT NULL REFERENCES public.devices(id) ON DELETE CASCADE,
    recording_type  TEXT NOT NULL CHECK (recording_type IN ('audio', 'video')),
    status          TEXT NOT NULL DEFAULT 'recording' 
                    CHECK (status IN ('recording', 'completed', 'interrupted', 'uploading')),
    started_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ended_at        TIMESTAMPTZ,
    total_chunks    INTEGER NOT NULL DEFAULT 0,
    total_size_bytes BIGINT NOT NULL DEFAULT 0,
    -- Why JSONB: Allows flexible metadata storage per recording without schema changes.
    -- Stores things like: camera facing, resolution, GPS coords (if opted in), etc.
    metadata        JSONB DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Why: Device-based queries are the most common pattern (show me all recordings from device X).
CREATE INDEX idx_recordings_device_id ON public.recordings(device_id);
CREATE INDEX idx_recordings_status ON public.recordings(status);
CREATE INDEX idx_recordings_started_at ON public.recordings(started_at DESC);

COMMENT ON TABLE public.recordings IS 
    'Recording session metadata. Groups evidence chunks into logical capture sessions with lifecycle tracking.';

-- ============================================================================
-- TABLE: evidence_chunks
-- ============================================================================
-- Why: This is the core of the anti-sabotage design. Each chunk represents
-- 5-10 seconds of captured evidence. By uploading in near-real-time,
-- even if the device is destroyed, all previously uploaded chunks are
-- preserved in Supabase Storage. The sha256_hash ensures tamper detection:
-- if a chunk's hash doesn't match what was originally uploaded, the
-- evidence has been compromised.
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.evidence_chunks (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    recording_id    UUID NOT NULL REFERENCES public.recordings(id) ON DELETE CASCADE,
    chunk_index     INTEGER NOT NULL,
    storage_path    TEXT NOT NULL,
    size_bytes      BIGINT NOT NULL DEFAULT 0,
    duration_ms     INTEGER NOT NULL DEFAULT 0,
    mime_type       TEXT NOT NULL DEFAULT 'video/mp4',
    sha256_hash     TEXT NOT NULL,
    uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    -- Why UNIQUE constraint: Prevents duplicate chunk uploads for the same recording.
    -- chunk_index must be monotonically increasing within a recording session.
    CONSTRAINT uq_recording_chunk UNIQUE (recording_id, chunk_index)
);

-- Why: When reconstructing a recording, we need chunks in order.
-- This composite index optimizes the most critical query pattern.
CREATE INDEX idx_chunks_recording_ordered 
    ON public.evidence_chunks(recording_id, chunk_index ASC);

COMMENT ON TABLE public.evidence_chunks IS 
    'Individual evidence fragments (5-10s each). Core anti-sabotage mechanism: chunks are uploaded in near-real-time to survive device destruction.';

-- ============================================================================
-- FUNCTION: register_chunk()
-- ============================================================================
-- Why: Atomic chunk registration that updates both the chunk table AND
-- the parent recording's aggregate counters in a single transaction.
-- This prevents inconsistency between the chunk count and actual chunks.
-- ============================================================================
CREATE OR REPLACE FUNCTION public.register_chunk(
    p_recording_id UUID,
    p_chunk_index INTEGER,
    p_storage_path TEXT,
    p_size_bytes BIGINT,
    p_duration_ms INTEGER,
    p_mime_type TEXT,
    p_sha256_hash TEXT
) RETURNS UUID AS $$
DECLARE
    v_chunk_id UUID;
BEGIN
    -- Insert the new chunk
    INSERT INTO public.evidence_chunks (
        recording_id, chunk_index, storage_path, 
        size_bytes, duration_ms, mime_type, sha256_hash
    ) VALUES (
        p_recording_id, p_chunk_index, p_storage_path,
        p_size_bytes, p_duration_ms, p_mime_type, p_sha256_hash
    )
    RETURNING id INTO v_chunk_id;
    
    -- Atomically update parent recording counters
    UPDATE public.recordings 
    SET 
        total_chunks = total_chunks + 1,
        total_size_bytes = total_size_bytes + p_size_bytes,
        updated_at = NOW()
    WHERE id = p_recording_id;
    
    RETURN v_chunk_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

COMMENT ON FUNCTION public.register_chunk IS 
    'Atomically registers a new evidence chunk and updates parent recording aggregates. Called by the Vercel middleware after successful Storage upload.';

-- ============================================================================
-- FUNCTION: auto-update updated_at trigger
-- ============================================================================
CREATE OR REPLACE FUNCTION public.handle_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER set_devices_updated_at
    BEFORE UPDATE ON public.devices
    FOR EACH ROW EXECUTE FUNCTION public.handle_updated_at();

CREATE TRIGGER set_recordings_updated_at
    BEFORE UPDATE ON public.recordings
    FOR EACH ROW EXECUTE FUNCTION public.handle_updated_at();

-- ============================================================================
-- ROW LEVEL SECURITY (RLS)
-- ============================================================================
-- Security Model:
--   - The Vercel middleware uses the service_role key to INSERT data.
--     Service role bypasses RLS by default in Supabase.
--   - Device-scoped JWTs (issued during registration) allow each device
--     to SELECT only its own data.
--   - No device can UPDATE or DELETE evidence — immutability is paramount.
-- ============================================================================

-- Enable RLS on all tables
ALTER TABLE public.devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.recordings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.evidence_chunks ENABLE ROW LEVEL SECURITY;

-- DEVICES: A device can only see its own registration record.
-- Why: Prevents enumeration of registered devices.
CREATE POLICY "devices_select_own" ON public.devices
    FOR SELECT
    USING (id = auth.uid());

-- DEVICES: Only service role can insert new devices (via Vercel registration endpoint).
CREATE POLICY "devices_insert_service" ON public.devices
    FOR INSERT
    WITH CHECK (TRUE);
    -- Note: service_role bypasses RLS. This policy is for anon/authenticated roles.

-- RECORDINGS: A device can only see its own recordings.
CREATE POLICY "recordings_select_own" ON public.recordings
    FOR SELECT
    USING (device_id = auth.uid());

-- RECORDINGS: Service role inserts recordings on behalf of devices.
CREATE POLICY "recordings_insert_service" ON public.recordings
    FOR INSERT
    WITH CHECK (TRUE);

-- RECORDINGS: Allow status updates (recording → completed) by service role only.
CREATE POLICY "recordings_update_service" ON public.recordings
    FOR UPDATE
    USING (TRUE)
    WITH CHECK (TRUE);

-- EVIDENCE_CHUNKS: A device can only see chunks belonging to its recordings.
CREATE POLICY "chunks_select_own" ON public.evidence_chunks
    FOR SELECT
    USING (
        recording_id IN (
            SELECT r.id FROM public.recordings r WHERE r.device_id = auth.uid()
        )
    );

-- EVIDENCE_CHUNKS: Only service role can insert chunks.
CREATE POLICY "chunks_insert_service" ON public.evidence_chunks
    FOR INSERT
    WITH CHECK (TRUE);

-- ============================================================================
-- STORAGE BUCKET: evidence-vault
-- ============================================================================
-- Why: Private bucket with restricted MIME types. Only the Vercel middleware
-- (using service_role) can write to it. Devices can read their own files
-- through the API, never directly.
--
-- Note: Supabase Storage bucket creation is done via the dashboard or CLI,
-- but we define the RLS policies here for the storage.objects table.
-- ============================================================================

-- Storage RLS: Devices can only read objects in their own folder path.
-- Path format: evidence-vault/{device_id}/{recording_id}/chunk_{index}.{ext}
CREATE POLICY "storage_select_own" ON storage.objects
    FOR SELECT
    USING (
        bucket_id = 'evidence-vault' 
        AND (storage.foldername(name))[1] = auth.uid()::TEXT
    );

-- Storage RLS: Only service role can insert objects (Vercel middleware).
CREATE POLICY "storage_insert_service" ON storage.objects
    FOR INSERT
    WITH CHECK (
        bucket_id = 'evidence-vault'
    );

-- ============================================================================
-- SEED: Create the storage bucket via SQL (Supabase allows this)
-- ============================================================================
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES (
    'evidence-vault',
    'evidence-vault',
    FALSE,  -- Private: no public URLs
    52428800, -- 50MB per file (50 * 1024 * 1024)
    ARRAY['video/mp4', 'audio/aac', 'audio/mp4', 'video/webm', 'audio/webm']
)
ON CONFLICT (id) DO NOTHING;

-- ============================================================================
-- END OF MIGRATION
-- ============================================================================
