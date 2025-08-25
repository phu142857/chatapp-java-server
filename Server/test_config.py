#!/usr/bin/env python3
"""
Test script to verify Firebase configuration
"""
import os
from dotenv import load_dotenv

def test_firebase_config():
    print("=== Firebase Configuration Test ===")
    
    # Load .env file
    load_dotenv()
    
    # Check required variables
    required_vars = [
        "FIREBASE_STORAGE_BUCKET",
        "FIREBASE_CREDENTIAL_PATH"
    ]
    
    all_good = True
    for var in required_vars:
        value = os.getenv(var)
        if value:
            print(f"✅ {var}: {value}")
        else:
            print(f"❌ {var}: NOT SET")
            all_good = False
    
    # Check credential file
    cred_path = os.getenv("FIREBASE_CREDENTIAL_PATH", "managechatserver-firebase-adminsdk-73kfe-411c3a20f7.json")
    if os.path.exists(cred_path):
        print(f"✅ Credential file exists: {cred_path}")
    else:
        print(f"❌ Credential file missing: {cred_path}")
        all_good = False
    
    print("\n=== Result ===")
    if all_good:
        print("✅ All configurations are good!")
        print("You can now run: python run_server.py")
    else:
        print("❌ Some configurations are missing!")
        print("Please check your .env file or set environment variables")
    
    return all_good

if __name__ == "__main__":
    test_firebase_config()
