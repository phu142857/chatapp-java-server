# ChatApp Java Server

FastAPI server for ChatApp Java with Firebase Realtime Database integration.

## Setup

### 1. Install Dependencies
```bash
pip install -r requirements.txt
```

### 2. Firebase Configuration
Make sure you have:
- Firebase Admin SDK credentials JSON file
- Firebase Realtime Database enabled
- Firestore database enabled (for metadata backup)

### 3. Environment Variables
Create a `.env` file with:
```env
FIREBASE_CREDENTIAL_PATH=managechatserver-firebase-adminsdk-73kfe-411c3a20f7.json
FIREBASE_API_KEY=your_api_key_here
```

### 4. Run Server
```bash
python run_server.py
```

Or directly with uvicorn:
```bash
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

## Features

### Avatar Upload (Realtime Database)
- **Endpoint**: `POST /uploadfile`
- **Authentication**: Bearer token required
- **File Types**: Images (JPEG, PNG, etc.)
- **Size Limit**: 5MB (optimized for Realtime Database)
- **Storage**: Firebase Realtime Database (base64 encoded)
- **Metadata**: Stored in Firestore for backup
- **Retrieval**: `GET /avatar/{uid}` - Get avatar image
- **Info**: `GET /avatar/{uid}/info` - Get avatar metadata

### Data Structure in Realtime Database
```
users/
├── {user_id}/
│   ├── avatar/
│   │   ├── uid: "user_id"
│   │   ├── filename: "avatar_user_id_timestamp.jpg"
│   │   ├── original_name: "original.jpg"
│   │   ├── content_type: "image/jpeg"
│   │   ├── size: 12345
│   │   ├── uploaded_at: "2025-08-13T22:30:00"
│   │   └── data: "base64_encoded_image_data"
│   ├── display_name: "User Name"
│   ├── email: "user@example.com"
│   └── avatar_url: "http://server:8000/avatar/user_id"
```

## API Endpoints

- `POST /uploadfile` - Upload avatar image to Realtime Database
- `GET /avatar/{uid}` - Get user avatar image
- `GET /avatar/{uid}/info` - Get user avatar metadata
- `POST /update_user` - Update user profile with avatar URL
- `POST /change_password` - Change user password
- `POST /restore_user_data` - Restore user data from Firestore to Realtime Database
- `GET /user_data_status` - Check user data integrity across databases
- `GET /` - Server status

## Data Integrity & Safety

### Avatar Upload Safety
- **No Data Loss**: Avatar uploads only affect the `avatar` section, preserving all other user data
- **Merge Operations**: All updates use merge operations instead of overwriting
- **Backup System**: User data is backed up in Firestore and can be restored if needed

### Data Restoration
If user data is accidentally corrupted in Realtime Database:
1. **Check Status**: Use `/user_data_status` to see what data exists where
2. **Restore Data**: Use `/restore_user_data` to restore from Firestore backup
3. **Automatic Recovery**: The system automatically preserves existing avatar data during restoration

### Best Practices
1. **Always use merge operations** for updates
2. **Check data status** after critical operations
3. **Keep Firestore as backup** for important user data
4. **Monitor logs** for any data integrity issues

## Advantages of Realtime Database

1. **No Storage Bucket Required** - Uses existing Realtime Database
2. **Faster Access** - Direct database access without external storage
3. **Simpler Setup** - No need to configure Firebase Storage
4. **Real-time Updates** - Changes reflect immediately across clients
5. **Cost Effective** - No additional storage costs

## Limitations

1. **File Size** - Limited to 5MB per image (Realtime Database constraint)
2. **Base64 Encoding** - Images are stored as text (larger size)
3. **No CDN** - Images served directly from server

## Troubleshooting

### Avatar Upload Issues
1. Check Firebase Realtime Database rules
2. Verify Firebase credentials file exists
3. Check server logs for detailed error messages
4. Ensure user is authenticated with valid token
5. Check file size (max 5MB)

### Common Errors
- `File too large` - Reduce image size (max 5MB)
- `Empty file` - Ensure image was selected properly
- `Invalid image data` - Check if image format is supported

## Logs
Server logs are saved to `server.log` and also displayed in console.
