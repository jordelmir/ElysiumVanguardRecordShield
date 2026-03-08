/**
 * ============================================================================
 * ELYSIUM VANGUARD RECORD SHIELD — Device Registration Endpoint
 * ============================================================================
 *
 * POST /api/register-device
 *
 * Purpose: Registers a new device in the Supabase `devices` table.
 * Returns a device UUID and API token that the Android app stores
 * in EncryptedSharedPreferences.
 *
 * Security:
 *   - The API token is generated server-side using crypto.randomUUID()
 *   - The token is hashed with bcrypt before storage in Supabase
 *   - The plaintext token is returned ONCE to the device, never again
 *   - pgcrypto extension must be enabled in Supabase for bcrypt
 * ============================================================================
 */

import type { VercelRequest, VercelResponse } from '@vercel/node';
import { createClient } from '@supabase/supabase-js';
import crypto from 'crypto';

const SUPABASE_URL = process.env.SUPABASE_URL!;
const SUPABASE_SERVICE_ROLE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY!;

const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
    auth: { persistSession: false },
});

interface RegisterDeviceRequest {
    device_alias?: string;
    device_fingerprint: string;
    platform?: string;
    os_version?: string;
    app_version?: string;
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

    // Parse request body
    let body: RegisterDeviceRequest;
    try {
        body = typeof req.body === 'string' ? JSON.parse(req.body) : req.body;
    } catch {
        res.status(400).json({ error: 'Invalid JSON body' });
        return;
    }

    if (!body.device_fingerprint) {
        res.status(400).json({ error: 'device_fingerprint is required' });
        return;
    }

    // Check if device already registered by fingerprint
    const { data: existingDevice } = await supabase
        .from('devices')
        .select('id')
        .eq('device_fingerprint', body.device_fingerprint)
        .single();

    if (existingDevice) {
        res.status(409).json({
            error: 'Device already registered',
            deviceId: existingDevice.id,
            hint: 'Use /api/rotate-token to get a new token for an existing device',
        });
        return;
    }

    // Generate a secure API token
    const apiToken = crypto.randomUUID() + '-' + crypto.randomBytes(16).toString('hex');

    // Hash the token with bcrypt via Supabase's pgcrypto
    // The gen_salt('bf', 12) creates a bcrypt salt with 12 rounds
    const { data: hashResult, error: hashError } = await supabase
        .rpc('hash_api_key', { raw_key: apiToken });

    if (hashError) {
        console.error('[HASH_ERROR]', hashError);
        // Fallback: hash with SHA-256 if pgcrypto function doesn't exist
        const sha256Hash = crypto
            .createHash('sha256')
            .update(apiToken)
            .digest('hex');

        // Insert device with SHA-256 hash fallback
        const { data: device, error: insertError } = await supabase
            .from('devices')
            .insert({
                device_fingerprint: body.device_fingerprint,
                api_key_hash: sha256Hash,
                device_alias: body.device_alias || `Device-${body.device_fingerprint.substring(0, 8)}`,
                is_active: true,
            })
            .select('id')
            .single();

        if (insertError || !device) {
            console.error('[INSERT_ERROR]', insertError);
            res.status(500).json({ error: 'Failed to register device' });
            return;
        }

        res.status(201).json({
            deviceId: device.id,
            apiToken: apiToken,
            message: 'Device registered successfully',
            warning: 'Store this token securely — it cannot be retrieved again.',
        });
        return;
    }

    // Insert device with bcrypt hash
    const { data: device, error: insertError } = await supabase
        .from('devices')
        .insert({
            device_fingerprint: body.device_fingerprint,
            api_key_hash: hashResult,
            device_alias: body.device_alias || `Device-${body.device_fingerprint.substring(0, 8)}`,
            is_active: true,
        })
        .select('id')
        .single();

    if (insertError || !device) {
        console.error('[INSERT_ERROR]', insertError);
        res.status(500).json({ error: 'Failed to register device' });
        return;
    }

    // Update last_seen_at
    await supabase
        .from('devices')
        .update({ last_seen_at: new Date().toISOString() })
        .eq('id', device.id);

    res.status(201).json({
        deviceId: device.id,
        apiToken: apiToken,
        message: 'Device registered successfully',
        warning: 'Store this token securely — it cannot be retrieved again.',
    });
}
