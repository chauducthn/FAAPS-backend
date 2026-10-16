# FAAPS avatar storage on S3

Use the private `faaps-bucket` bucket in `ap-southeast-1`. Keep Block Public Access enabled and ACLs disabled. The browser sends images to the backend, not to S3, so no bucket CORS or public bucket policy is needed.

## Give the backend access

In AWS IAM, create a customer-managed policy and attach it to the identity used by the backend (an IAM role in deployment; a development profile with temporary credentials locally):

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
    "Resource": "arn:aws:s3:::faaps-bucket/avatars/*"
  }]
}
```

Do not use the AWS root account or commit AWS credentials. The AWS SDK uses its default credential provider chain. For local development, use a short-lived AWS CLI profile or other temporary credentials, then set `AWS_PROFILE` if that profile is not the default.

Set these environment variables before starting Spring Boot:

```powershell
$env:FAAPS_AVATAR_ENABLED = 'true'
$env:FAAPS_AVATAR_BUCKET = 'faaps-bucket'
$env:FAAPS_AVATAR_REGION = 'ap-southeast-1'
```

Admin uploads `multipart/form-data` to `POST /api/admin/users/{id}/avatar` using the `file` field. Accepted types are JPEG, PNG, and WebP, up to 2 MB. The response's `avatarUrl` is an authenticated backend URL. The frontend should use it as the image source. Uploads require the Admin session cookie and a CSRF token; image reads require an authenticated session. The bucket itself remains private.
