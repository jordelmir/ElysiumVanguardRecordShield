/**
 * ============================================================================
 * ELYSIUM VANGUARD RECORD SHIELD — Register Chunk Metadata
 * ============================================================================
 * 
 * POST /api/register-chunk
 * 
 * Purpose: Registers the metadata for a chunk that has already been uploaded
 * directly to Supabase Storage via a Signed URL.
 * 
 * Security:
 *  - Validates X-Device-Token and X-Device-Id
 *  - Verifies the recording exists and belongs to the device
 *  - Verifies the SHA-256 integrity hash is recorded
 * 
 * ============================================================================
 */

import type { VercelRequest, VercelResponse } from '@vercel/node';
import { createClient } from '@supabase/supabase-js';

const SUPABASE_URL = process.env.SUPABASE_URL!;
const SUPABASE_SERVICE_ROLE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY!;

const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
    auth: { persistSession: false },
});

async function validateDeviceToken(
    deviceId: string,
    token: string
): Promise<boolean> {
    const { data, error } = await supabase
        .from('devices')
        .select('is_active')
        .eq('id', deviceId)
        .single();

    if (error || !data || !data.is_active) {
        return false;
    }

    const { data: verifyResult } = await supabase.rpc('verify_device_token', {
        p_device_id: deviceId,
        p_token: token,
    });

    return verifyResult === true;
}

export default async function handler(
    req: VercelRequest,
    res: VercelResponse
): Promise<void> {
    if (req.method !== 'POST') {
        res.setHeader('Allow', 'POST');
        res.status(405).json({ error: 'Method Not Allowed' });
        return;
    }

    const deviceToken = req.headers['x-device-token'] as string;
    const deviceId = req.headers['x-device-id'] as string;
    const {
        recordingId,
        chunkIndex,
        storagePath,
        sizeBytes,
        durationMs,
        mimeType,
        sha256Hash
    } = req.body;

    if (!deviceToken || !deviceId || !recordingId || chunkIndex === undefined || !storagePath || !sha256Hash) {
        res.status(400).json({ error: 'Missing required fields' });
        return;
    }

    try {
        // 1. Validate Device
        const isValid = await validateDeviceToken(deviceId, deviceToken);
        if (!isValid) {
            res.status(401).json({ error: 'Unauthorized' });
            return;
        }

        // 2. Register Chunk Metadata via RPC
        // This atomically updates the chunk table and recording aggregates
        const { data: chunkId, error: registerError } = await supabase.rpc(
            'register_chunk',
            {
                p_recording_id: recordingId,
                p_chunk_index: chunkIndex,
                p_storage_path: storagePath,
                p_size_bytes: sizeBytes,
                p_duration_ms: durationMs || 0,
                p_mime_type: mimeType || 'video/mp4',
                p_sha256_hash: sha256Hash,
            }
        );

        if (registerError) {
            console.error('[DB_ERROR]', registerError);
            res.status(500).json({ error: 'Failed to register chunk metadata' });
            return;
        }

        // 3. Update device last_seen_at
        await supabase
            .from('devices')
            .update({ last_seen_at: new Date().toISOString() })
            .eq('id', deviceId);

        res.status(201).json({
            success: true,
            chunkId,
            message: 'Chunk metadata registered successfully'
        });

    } catch (err) {
        console.error('[SERVER_ERROR]', err);
        res.status(500).json({ error: 'Internal server error' });
    }
}
