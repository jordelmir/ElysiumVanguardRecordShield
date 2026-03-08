/**
 * ============================================================================
 * ELYSIUM VANGUARD RECORD SHIELD — Evidence Upload Endpoint
 * ============================================================================
 * 
 * POST /api/upload-evidence
 * 
 * Purpose: Receives binary evidence chunks from the Android app and stores
 * them securely in Supabase Storage. This is the critical anti-sabotage
 * pipeline — each chunk represents 5-10 seconds of captured evidence
 * that is immediately persisted in the cloud.
 * 
 * Security Model:
 *   - Device authentication via X-Device-Token header
 *   - Token is validated against the devices table (api_key_hash)
 *   - SHA-256 integrity hash is verified on every chunk
 *   - Service role key is used for Supabase writes (never exposed to client)
 * 
 * Headers Required:
 *   - X-Device-Token: The device's API key (validated against bcrypt hash in DB)
 *   - X-Device-Id: UUID of the registered device
 *   - X-Recording-Id: UUID of the active recording session
 *   - X-Chunk-Index: Zero-based integer index of this chunk
 *   - X-Chunk-Hash: SHA-256 hex digest of the chunk binary
 *   - Content-Type: video/mp4, audio/aac, audio/mp4, video/webm, or audio/webm
 * 
 * ============================================================================
 */

import type { VercelRequest, VercelResponse } from '@vercel/node';
import { createClient } from '@supabase/supabase-js';
import { createHash } from 'crypto';

// ============================================================================
// Why environment variables: Service role key and Supabase URL must NEVER
// be hardcoded. They're injected via Vercel's environment variable system,
// which encrypts them at rest and in transit.
// ============================================================================
const SUPABASE_URL = process.env.SUPABASE_URL!;
const SUPABASE_SERVICE_ROLE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY!;

// Why service_role: This key bypasses RLS, allowing the middleware to write
// to any table/bucket. The Android app NEVER has access to this key.
const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
    auth: { persistSession: false },
});

// Allowed MIME types — must match the Supabase bucket configuration
const ALLOWED_MIME_TYPES = new Set([
    'video/mp4',
    'audio/aac',
    'audio/mp4',
    'video/webm',
    'audio/webm',
]);

// File extension mapping for storage paths
const MIME_TO_EXT: Record<string, string> = {
    'video/mp4': 'mp4',
    'audio/aac': 'aac',
    'audio/mp4': 'm4a',
    'video/webm': 'webm',
    'audio/webm': 'weba',
};

/**
 * Validates the device token against the stored bcrypt hash in the database.
 * 
 * Why we validate here and not in Supabase Auth: The Android device doesn't
 * use traditional Supabase Auth (email/password). Instead, it uses a custom
 * API key system where each device gets a unique rotatable key during
 * registration. This gives us more control over device authentication
 * without requiring user accounts.
 */
async function validateDeviceToken(
    deviceId: string,
    token: string
): Promise<boolean> {
    const { data, error } = await supabase
        .from('devices')
        .select('api_key_hash, is_active')
        .eq('id', deviceId)
        .single();

    if (error || !data || !data.is_active) {
        return false;
    }

    // Why verify via pgcrypto: We use Supabase's built-in pgcrypto extension
    // to verify the token against the stored bcrypt hash. This avoids shipping
    // bcrypt to the Edge Function and keeps the hash verification in the DB.
    const { data: verifyResult } = await supabase.rpc('verify_device_token', {
        p_device_id: deviceId,
        p_token: token,
    });

    return verifyResult === true;
}

/**
 * Verifies SHA-256 integrity of the uploaded chunk.
 * 
 * Why: Ensures the chunk wasn't corrupted or tampered with during transit.
 * The Android app computes the hash before upload, and we recompute it
 * here. If they don't match, the chunk is rejected.
 */
function verifyChunkIntegrity(buffer: Buffer, expectedHash: string): boolean {
    const actualHash = createHash('sha256').update(buffer).digest('hex');
    return actualHash === expectedHash;
}

/**
 * Constructs the storage path for a chunk.
 * 
 * Path format: {device_id}/{recording_id}/chunk_{index}.{ext}
 * 
 * Why this structure: 
 *   - device_id as root folder enables RLS on storage.objects
 *   - recording_id groups all chunks of a session
 *   - chunk_{index} ensures ordered reconstruction
 */
function buildStoragePath(
    deviceId: string,
    recordingId: string,
    chunkIndex: number,
    mimeType: string
): string {
    const ext = MIME_TO_EXT[mimeType] || 'mp4';
    return `${deviceId}/${recordingId}/chunk_${String(chunkIndex).padStart(5, '0')}.${ext}`;
}

// ============================================================================
// MAIN HANDLER
// ============================================================================
export default async function handler(
    req: VercelRequest,
    res: VercelResponse
): Promise<void> {
    // Only accept POST requests
    if (req.method !== 'POST') {
        res.setHeader('Allow', 'POST');
        res.status(405).json({
            error: 'Method Not Allowed',
            message: 'Only POST requests are accepted.',
        });
        return;
    }

    // ---- Extract and validate headers ----
    const deviceToken = req.headers['x-device-token'] as string;
    const deviceId = req.headers['x-device-id'] as string;
    const recordingId = req.headers['x-recording-id'] as string;
    const chunkIndexStr = req.headers['x-chunk-index'] as string;
    const chunkHash = req.headers['x-chunk-hash'] as string;
    const contentType = req.headers['content-type'] as string;

    // Validate all required headers are present
    if (!deviceToken || !deviceId || !recordingId || !chunkIndexStr || !chunkHash) {
        res.status(400).json({
            error: 'Bad Request',
            message: 'Missing required headers: X-Device-Token, X-Device-Id, X-Recording-Id, X-Chunk-Index, X-Chunk-Hash',
        });
        return;
    }

    // Validate chunk index is a non-negative integer
    const chunkIndex = parseInt(chunkIndexStr, 10);
    if (isNaN(chunkIndex) || chunkIndex < 0) {
        res.status(400).json({
            error: 'Bad Request',
            message: 'X-Chunk-Index must be a non-negative integer.',
        });
        return;
    }

    // Validate MIME type
    if (!contentType || !ALLOWED_MIME_TYPES.has(contentType)) {
        res.status(415).json({
            error: 'Unsupported Media Type',
            message: `Content-Type must be one of: ${[...ALLOWED_MIME_TYPES].join(', ')}`,
        });
        return;
    }

    // ---- Authenticate device ----
    try {
        const isValidDevice = await validateDeviceToken(deviceId, deviceToken);
        if (!isValidDevice) {
            res.status(401).json({
                error: 'Unauthorized',
                message: 'Invalid device token or device is deactivated.',
            });
            return;
        }
    } catch (authError) {
        console.error('[AUTH_ERROR]', authError);
        res.status(500).json({
            error: 'Internal Server Error',
            message: 'Device authentication failed unexpectedly.',
        });
        return;
    }

    // ---- Read and validate chunk body ----
    // Why Buffer: Vercel automatically parses the body into a Buffer when
    // the content-type is not JSON/form-data. We need the raw bytes.
    const chunks: Buffer[] = [];

    // Collect the raw body
    const rawBody = req.body;
    let chunkBuffer: Buffer;

    if (Buffer.isBuffer(rawBody)) {
        chunkBuffer = rawBody;
    } else if (typeof rawBody === 'string') {
        chunkBuffer = Buffer.from(rawBody, 'binary');
    } else {
        // For cases where body parsing gives us something unexpected
        chunkBuffer = Buffer.from(JSON.stringify(rawBody));
    }

    if (chunkBuffer.length === 0) {
        res.status(400).json({
            error: 'Bad Request',
            message: 'Request body is empty. Expected binary chunk data.',
        });
        return;
    }

    // ---- Verify integrity ----
    if (!verifyChunkIntegrity(chunkBuffer, chunkHash)) {
        res.status(422).json({
            error: 'Integrity Check Failed',
            message: 'SHA-256 hash of uploaded data does not match X-Chunk-Hash header.',
        });
        return;
    }

    // ---- Verify recording exists and belongs to this device ----
    const { data: recording, error: recordingError } = await supabase
        .from('recordings')
        .select('id, device_id, status')
        .eq('id', recordingId)
        .eq('device_id', deviceId)
        .single();

    if (recordingError || !recording) {
        res.status(404).json({
            error: 'Not Found',
            message: 'Recording not found or does not belong to this device.',
        });
        return;
    }

    // ---- Upload chunk to Supabase Storage ----
    const storagePath = buildStoragePath(deviceId, recordingId, chunkIndex, contentType);

    const { error: uploadError } = await supabase.storage
        .from('evidence-vault')
        .upload(storagePath, chunkBuffer, {
            contentType,
            upsert: false, // Why: Never overwrite — immutability is a security requirement
        });

    if (uploadError) {
        // Handle duplicate chunk gracefully
        if (uploadError.message?.includes('already exists')) {
            res.status(409).json({
                error: 'Conflict',
                message: `Chunk ${chunkIndex} already uploaded for this recording.`,
            });
            return;
        }

        console.error('[STORAGE_ERROR]', uploadError);
        res.status(500).json({
            error: 'Storage Error',
            message: 'Failed to upload chunk to secure storage.',
        });
        return;
    }

    // ---- Register chunk metadata in database ----
    const { data: chunkId, error: registerError } = await supabase.rpc(
        'register_chunk',
        {
            p_recording_id: recordingId,
            p_chunk_index: chunkIndex,
            p_storage_path: storagePath,
            p_size_bytes: chunkBuffer.length,
            p_duration_ms: 0, // Will be updated by the Android app with actual duration
            p_mime_type: contentType,
            p_sha256_hash: chunkHash,
        }
    );

    if (registerError) {
        console.error('[DB_ERROR]', registerError);
        // If DB registration fails but storage succeeded, we still return success
        // because the evidence IS safe. The metadata can be reconciled later.
        res.status(201).json({
            success: true,
            warning: 'Chunk uploaded to storage but metadata registration failed. Evidence is safe.',
            chunk: {
                index: chunkIndex,
                storage_path: storagePath,
                size_bytes: chunkBuffer.length,
                sha256_hash: chunkHash,
            },
        });
        return;
    }

    // ---- Update device last_seen_at ----
    await supabase
        .from('devices')
        .update({ last_seen_at: new Date().toISOString() })
        .eq('id', deviceId);

    // ---- Success response ----
    res.status(201).json({
        success: true,
        chunk: {
            id: chunkId,
            index: chunkIndex,
            storage_path: storagePath,
            size_bytes: chunkBuffer.length,
            sha256_hash: chunkHash,
            uploaded_at: new Date().toISOString(),
        },
    });
}
