-- APNs (the iPhone) as a third push transport: the endpoint is the device
-- token in hex, checked by the server (validatePushEndpoint), not here.
ALTER TABLE push_endpoints DROP CONSTRAINT push_endpoints_transport_check;
ALTER TABLE push_endpoints ADD CONSTRAINT push_endpoints_transport_check
    CHECK (transport IN ('fcm', 'unifiedpush', 'apns'));
