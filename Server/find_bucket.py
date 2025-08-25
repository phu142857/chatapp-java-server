#!/usr/bin/env python3
"""
Script to find the correct Firebase Storage bucket name
"""
import os
from google.cloud import storage
from dotenv import load_dotenv

def test_bucket_names():
    print("=== Testing Firebase Storage Bucket Names ===")
    
    # Load environment variables
    load_dotenv()
    
    # Get credential path
    cred_path = os.getenv("FIREBASE_CREDENTIAL_PATH", "managechatserver-firebase-adminsdk-73kfe-411c3a20f7.json")
    
    if not os.path.exists(cred_path):
        print(f"❌ Credential file not found: {cred_path}")
        return False
    
    try:
        # Initialize storage client
        storage_client = storage.Client.from_service_account_json(cred_path)
        print(f"✅ Connected to Firebase with credentials: {cred_path}")
        
        # List all buckets
        print("\n=== Available Buckets ===")
        buckets = list(storage_client.list_buckets())
        if buckets:
            for bucket in buckets:
                print(f"✅ Found bucket: {bucket.name}")
                print(f"   Location: {bucket.location}")
                print(f"   Storage Class: {bucket.storage_class}")
                print()
        else:
            print("❌ No buckets found in this project")
        
        # Test common bucket name patterns
        project_id = "managechatserver"
        test_names = [
            f"{project_id}.appspot.com",
            f"{project_id}-storage",
            f"{project_id}-default-rtdb.asia-southeast1.firebasedatabase.app",
            f"gs://{project_id}.appspot.com"
        ]
        
        print("=== Testing Common Bucket Names ===")
        working_bucket = None
        
        for bucket_name in test_names:
            try:
                # Remove gs:// prefix if present
                clean_name = bucket_name.replace("gs://", "")
                bucket = storage_client.bucket(clean_name)
                
                # Try to get bucket info
                bucket.reload()
                print(f"✅ {bucket_name} - EXISTS and accessible")
                working_bucket = clean_name
                break
                
            except Exception as e:
                print(f"❌ {bucket_name} - {str(e)}")
        
        if working_bucket:
            print(f"\n🎯 RECOMMENDED BUCKET: {working_bucket}")
            print(f"Add this to your .env file:")
            print(f"FIREBASE_STORAGE_BUCKET={working_bucket}")
        else:
            print("\n❌ No working bucket found!")
            print("Please check Firebase Console > Storage")
            print("Or create a new Storage bucket in Firebase Console")
        
        return working_bucket is not None
        
    except Exception as e:
        print(f"❌ Error connecting to Firebase: {e}")
        return False

if __name__ == "__main__":
    test_bucket_names()
