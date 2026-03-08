/**
 * ============================================================================
 * ELYSIUM VANGUARD RECORD SHIELD — Get Signed Upload URL
 * ============================================================================
 * 
 * GET /api/get-upload-url
 * 
 * Purpose: Generates a pre-signed upload URL for Supabase Storage.
 * This allows the Android app to upload large video chunks directly to
 * Supabase, bypassing Vercel's payload limits (4.5MB) and timeouts.
 * 
 * Security:
 *  - Validates X-Device-Token and X-Device-Id
 *  - Verifies the recording exists and belongs to the device
 *  - Generates a single-use, time-limited (60s) signed URL
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

const MIME_TO_EXT: Record<string, string> = {
    'video/mp4': 'mp4',
    'audio/aac': 'aac',
    'audio/mp4': 'm4a',
    'video/webm': 'webm',
    'audio/webm': 'weba',
};

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
    const recordingId = req.headers['x-recording-id'] as string;
    const chunkIndexStr = req.headers['x-chunk-index'] as string;
    const contentType = req.headers['content-type'] as string;

    if (!deviceToken || !deviceId || !recordingId || !chunkIndexStr || !contentType) {
        res.status(400).json({ error: 'Missing required headers' });
        return;
    }

    const chunkIndex = parseInt(chunkIndexStr, 10);
    if (isNaN(chunkIndex)) {
        res.status(400).json({ error: 'Invalid chunk index' });
        return;
    }

    try {
        // 1. Validate Device
        const isValid = await validateDeviceToken(deviceId, deviceToken);
        if (!isValid) {
            res.status(401).json({ error: 'Unauthorized' });
            return;
        }

        // 2. Verify Recording
        const { data: recording, error: recError } = await supabase
            .from('recordings')
            .select('id')
            .eq('id', recordingId)
            .eq('device_id', deviceId)
            .single();

        if (recError || !recording) {
            res.status(404).json({ error: 'Recording not found' });
            return;
        }

        // 3. Construct Path
        const ext = MIME_TO_EXT[contentType] || 'mp4';
        const storagePath = `${deviceId}/${recordingId}/chunk_${String(chunkIndex).padStart(5, '0')}.${ext}`;

        // 4. Generate Signed URL
        // createSignedUploadUrl generates a URL for a file that does NOT exist yet.
        const { data, error: uploadError } = await supabase.storage
            .from('evidence-vault')
            .createSignedUploadUrl(storagePath);

        if (uploadError || !data) {
            console.error('[STORAGE_ERROR]', uploadError);
            res.status(500).json({ error: 'Failed to generate upload URL' });
            return;
        }

        // 5. Update device last_seen_at
        await supabase
            .from('devices')
            .update({ last_seen_at: new Date().toISOString() })
            .eq('id', deviceId);

        res.status(200).json({
            signedUrl: data.signedUrl,
            token: data.token, // Some versions return a token to be used in the URL
            path: storagePath,
            message: 'Signed upload URL generated successfully'
        });

    } catch (err) {
        console.error('[SERVER_ERROR]', err);
        res.status(500).json({ error: 'Internal server error' });
    }
}
