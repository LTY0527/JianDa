SELECT id, username, role, status, organization_id, LEFT(password_hash,20) AS pw_prefix FROM staff_user WHERE username='platform_admin';
