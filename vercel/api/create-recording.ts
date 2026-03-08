/**
 * ============================================================================
 * ELYSIUM VANGUARD RECORD SHIELD — Create Recording Endpoint
 * ============================================================================
 * 
 * POST /api/create-recording
 * 
 * Purpose: Creates a new recording session in Supabase when the Android
 * device starts recording. Returns the Supabase recording UUID that the
 * device uses as X-Recording-Id for subsequent chunk uploads.
 * ============================================================================
 */

import type { VercelRequest, VercelResponse } from '@vercel/node';
import { createClient } from '@supabase/supabase-js';

const SUPABASE_URL = process.env.SUPABASE_URL!;
const SUPABASE_SERVICE_ROLE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY!;

const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
    auth: { persistSession: false },
});

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

    if (!deviceToken || !deviceId) {
        res.status(400).json({ error: 'Missing X-Device-Token or X-Device-Id headers' });
        return;
    }

    // Validate device exists and is active
    const { data: device, error: deviceError } = await supabase
        .from('devices')
        .select('id, is_active')
        .eq('id', deviceId)
        .single();

    if (deviceError || !device || !device.is_active) {
        res.status(401).json({ error: 'Invalid or inactive device' });
        return;
    }

    // Parse recording type from body
    let recordingType = 'video';
    try {
        const body = typeof req.body === 'string' ? JSON.parse(req.body) : req.body;
        recordingType = body?.recording_type || 'video';
    } catch {
        // Default to video
    }

    // Create recording in Supabase
    const { data: recording, error: recordingError } = await supabase
        .from('recordings')
        .insert({
            device_id: deviceId,
            recording_type: recordingType,
            status: 'recording',
            started_at: new Date().toISOString(),
        })
        .select('id')
        .single();

    if (recordingError || !recording) {
        console.error('[DB_ERROR]', recordingError);
        res.status(500).json({ error: 'Failed to create recording session' });
        return;
    }

    // Update device last_seen_at
    await supabase
        .from('devices')
        .update({ last_seen_at: new Date().toISOString() })
        .eq('id', deviceId);

    res.status(201).json({
        recordingId: recording.id,
        message: 'Recording session created successfully',
    });
}
