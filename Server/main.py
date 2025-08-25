import os
from fastapi import FastAPI
import firebase_admin
from firebase_admin import credentials
from dotenv import load_dotenv
from fastapi import HTTPException
from pydantic import BaseModel, EmailStr
from firebase_admin import auth, firestore
from datetime import datetime
from typing import Optional, Union
from fastapi import APIRouter, Body, Depends, Request
import requests
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from fastapi import Path
import uuid
from fastapi import UploadFile, File
from google.cloud import storage
from fastapi import WebSocket, WebSocketDisconnect
from typing import Dict
from pydantic import BaseModel
from datetime import datetime
from fastapi import Query
import firebase_admin
from firebase_admin import db
from fastapi import status
import logging

# Load environment variables from .env file
load_dotenv(dotenv_path=os.path.join(os.path.dirname(__file__), '.env'))

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.StreamHandler(),
        logging.FileHandler('server.log')
    ]
)
logger = logging.getLogger(__name__)

FIREBASE_CREDENTIAL_PATH = os.getenv('FIREBASE_CREDENTIAL_PATH', 'Server/managechatserver-firebase-adminsdk-73kfe-411c3a20f7.json')
FIREBASE_STORAGE_BUCKET = os.getenv('FIREBASE_STORAGE_BUCKET', 'managechatserver.appspot.com')

# Check Firebase Storage configuration
if not FIREBASE_STORAGE_BUCKET:
    logger.warning("FIREBASE_STORAGE_BUCKET not configured - file upload will be disabled")
else:
    logger.info(f"Firebase Storage configured: {FIREBASE_STORAGE_BUCKET}")

# Initialize Firebase Admin SDK
if not firebase_admin._apps:
    cred = credentials.Certificate(FIREBASE_CREDENTIAL_PATH)
    firebase_admin.initialize_app(cred, {
        'databaseURL': 'https://managechatserver-default-rtdb.asia-southeast1.firebasedatabase.app/'
    })

# Firestore client
firestore_client = firestore.client()

# Đảm bảo đã khởi tạo Realtime Database URL khi init Firebase app:
# firebase_admin.initialize_app(cred, {'databaseURL': 'https://<your-project-id>.firebaseio.com'})

def update_user_realtime_db(uid, user_data):
    """Update user data in Realtime Database by merging, not overwriting"""
    ref = db.reference(f'users/{uid}')
    # Get existing data first
    existing_data = ref.get()
    if existing_data:
        # Merge new data with existing data
        merged_data = {**existing_data, **user_data}
        ref.set(merged_data)
    else:
        # If no existing data, just set the new data
        ref.set(user_data)

app = FastAPI()

active_connections: Dict[str, WebSocket] = {}

@app.get("/")
def root():
    return {"message": "Chat backend with FastAPI & Firebase is running!"}

class RegisterRequest(BaseModel):
    email: EmailStr
    password: str
    display_name: str = None

@app.post("/authregister")
def auth_register(data: RegisterRequest):
    try:
        # Nếu không có display_name, lấy phần trước @ của email
        display_name = data.display_name or data.email.split("@")[0]
        # Tạo user trên Firebase Auth
        user = auth.create_user(
            email=data.email,
            password=data.password,
            display_name=display_name
        )
        # Lưu thông tin user vào Firestore
        user_doc = {
            "uid": user.uid,
            "email": user.email,
            "display_name": user.display_name,
            "created_at": datetime.utcnow().isoformat()
        }
        firestore_client.collection("users").document(user.uid).set(user_doc)
        # Lưu vào Realtime Database
        update_user_realtime_db(user.uid, user_doc)
        return {"message": "User registered successfully", "uid": user.uid}
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e)) 

class LoginRequest(BaseModel):
    email: Optional[EmailStr] = None
    password: Optional[str] = None
    id_token: Optional[str] = None

# Đọc FIREBASE_API_KEY từ .env
FIREBASE_API_KEY = os.getenv('FIREBASE_API_KEY', 'AIzaSyB9dIzlKszBNuEC4oRcUUZmYw9h3nM91Q0')

router = APIRouter()

@router.post("/login")
async def login(user: RegisterRequest):
    if not FIREBASE_API_KEY:
        raise HTTPException(status_code=500, detail="FIREBASE_API_KEY not configured")
    url = f"https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key={FIREBASE_API_KEY}"
    payload = {
        "email": user.email,
        "password": user.password,
        "returnSecureToken": True
    }
    resp = requests.post(url, json=payload)
    if resp.status_code == 200:
        data = resp.json()
        return {
            "idToken": data["idToken"],
            "refreshToken": data["refreshToken"],
            "expiresIn": data["expiresIn"],
            "localId": data["localId"]
        }
    else:
        raise HTTPException(status_code=400, detail=resp.json().get("error", {}).get("message", "Login failed"))

@router.post("/verify-token")
async def verify_token(id_token: str = Body(..., embed=True)):
    try:
        decoded_token = auth.verify_id_token(id_token)
        return {"uid": decoded_token["uid"]}
    except Exception as e:
        print("Verify error:", e)  # Thêm dòng này để xem lỗi chi tiết
        raise HTTPException(status_code=401, detail="Invalid token")

security = HTTPBearer()

def get_current_user(credentials: HTTPAuthorizationCredentials = Depends(security)):
    token = credentials.credentials
    try:
        # Allow small clock skew on first login
        decoded_token = auth.verify_id_token(token, clock_skew_seconds=60)
        return decoded_token  # Có thể trả về dict hoặc custom user object
    except Exception as e:
        print("Auth dependency error:", e)
        raise HTTPException(status_code=401, detail="Invalid or expired token")

@app.get("/protected")
def protected_route(current_user: dict = Depends(get_current_user)):
    return {"message": "You are authenticated!", "user": current_user}

@app.get("/usersme")
def get_current_user_info(current_user: dict = Depends(get_current_user)):
    uid = current_user["uid"]
    user_doc = firestore_client.collection("users").document(uid).get()
    if user_doc.exists:
        return user_doc.to_dict()
    else:
        raise HTTPException(status_code=404, detail="User not found in Firestore")

class AddFriendRequest(BaseModel):
    target_user_email: Optional[EmailStr] = None
    target_user_id: Optional[str] = None

@app.post("/friendsadd")
def add_friend(
    data: AddFriendRequest,
    current_user: dict = Depends(get_current_user)
):
    my_uid = current_user["uid"]
    # Tìm user đích
    target_user = None
    if data.target_user_id:
        target_doc = firestore_client.collection("users").document(data.target_user_id).get()
        if target_doc.exists:
            target_user = target_doc.to_dict()
    elif data.target_user_email:
        users_ref = firestore_client.collection("users")
        query = users_ref.where("email", "==", data.target_user_email).limit(1).stream()
        for doc in query:
            target_user = doc.to_dict()
            break
    else:
        raise HTTPException(status_code=400, detail="Must provide target_user_email or target_user_id")
    if not target_user:
        raise HTTPException(status_code=404, detail="Target user not found")
    target_uid = target_user["uid"]
    # Không cho tự thêm chính mình
    if my_uid == target_uid:
        raise HTTPException(status_code=400, detail="Cannot add yourself as a friend")
    # Kiểm tra đã là bạn bè chưa (subcollection friends)
    friend_ref = firestore_client.collection("users").document(my_uid).collection("friends").document(target_uid)
    if friend_ref.get().exists:
        raise HTTPException(status_code=400, detail="Already friends")
    # Lưu mối quan hệ bạn bè hai chiều Firestore
    friend_ref.set({"friend_uid": target_uid, "added_at": datetime.utcnow().isoformat()})
    reverse_ref = firestore_client.collection("users").document(target_uid).collection("friends").document(my_uid)
    reverse_ref.set({"friend_uid": my_uid, "added_at": datetime.utcnow().isoformat()})
    # Lưu vào Realtime Database
    db.reference(f'friends/{my_uid}/{target_uid}').set({"friend_uid": target_uid, "added_at": datetime.utcnow().isoformat()})
    db.reference(f'friends/{target_uid}/{my_uid}').set({"friend_uid": my_uid, "added_at": datetime.utcnow().isoformat()})

    # Tạo document private_chats rỗng nếu chưa có
    chat_id = "_".join(sorted([my_uid, target_uid]))
    private_chat_ref = firestore_client.collection("private_chats").document(chat_id)
    if not private_chat_ref.get().exists:
        private_chat_ref.set({
            "participants": [my_uid, target_uid],
            "last_message": "",
            "last_message_time": "",
            "created_at": datetime.utcnow().isoformat()
        })

    return {"message": "Friend added successfully", "friend_uid": target_uid, "friend_email": target_user["email"]}

@app.get("/friends")
def get_friends(current_user: dict = Depends(get_current_user)):
    my_uid = current_user["uid"]
    friends_ref = firestore_client.collection("users").document(my_uid).collection("friends")
    friends_docs = friends_ref.stream()
    friend_uids = [doc.id for doc in friends_docs]
    # Lấy thông tin cơ bản của từng bạn bè
    friends_info = []
    for uid in friend_uids:
        user_doc = firestore_client.collection("users").document(uid).get()
        if user_doc.exists:
            user_data = user_doc.to_dict()
            friends_info.append({
                "uid": user_data["uid"],
                "display_name": user_data.get("display_name"),
                "email": user_data.get("email")
            })
    return {"friends": friends_info}

class SendPrivateMessageRequest(BaseModel):
    receiver_uid: str
    message_content: str
    message_type: str = "text"  # text, image, file
    file_url: Optional[str] = None

@app.post("/messagessendprivate")
def send_private_message(
    data: SendPrivateMessageRequest,
    current_user: dict = Depends(get_current_user)
):
    sender_uid = current_user["uid"]
    receiver_uid = data.receiver_uid
    # Đảm bảo không gửi cho chính mình
    if sender_uid == receiver_uid:
        raise HTTPException(status_code=400, detail="Cannot send message to yourself")
    # Tạo chat_id duy nhất cho cặp user (uidA_uidB, uidA < uidB)
    chat_id = "_".join(sorted([sender_uid, receiver_uid]))
    messages_ref = firestore_client.collection("private_chats").document(chat_id).collection("messages")
    message_data = {
        "sender_uid": sender_uid,
        "receiver_uid": receiver_uid,
        "message_content": data.message_content,
        "timestamp": datetime.utcnow().isoformat(),
        "message_type": data.message_type,
        "file_url": data.file_url
    }
    msg_ref = messages_ref.document()
    msg_ref.set(message_data)
    # Cập nhật last_message, last_message_time ở document chat
    firestore_client.collection("private_chats").document(chat_id).set({
        "participants": [sender_uid, receiver_uid],
        "last_message": data.message_content,
        "last_message_time": message_data["timestamp"],
        "created_at": message_data["timestamp"]
    }, merge=True)
    return {"message": "Message sent", "message_id": msg_ref.id}

@app.get("/messagesprivate/{receiver_uid}")
def get_private_messages(
    receiver_uid: str = Path(...),
    current_user: dict = Depends(get_current_user)
):
    sender_uid = current_user["uid"]
    if sender_uid == receiver_uid:
        raise HTTPException(status_code=400, detail="Cannot get messages with yourself")
    chat_id = "_".join(sorted([sender_uid, receiver_uid]))
    messages_ref = firestore_client.collection("private_chats").document(chat_id).collection("messages")
    # Lấy tất cả tin nhắn, sắp xếp theo timestamp tăng dần
    messages = []
    for doc in messages_ref.order_by("timestamp").stream():
        msg = doc.to_dict()
        msg["id"] = doc.id
        messages.append(msg)
    return {"messages": messages}

class CreateGroupRequest(BaseModel):
    group_name: str
    member_uids: list[str]

@app.post("/groupscreate")
def create_group(
    data: CreateGroupRequest,
    current_user: dict = Depends(get_current_user)
):
    admin_uid = current_user["uid"]
    # Đảm bảo admin nằm trong danh sách thành viên
    member_uids = list(set(data.member_uids + [admin_uid]))
    group_id = str(uuid.uuid4())
    group_doc = {
        "group_id": group_id,
        "group_name": data.group_name,
        "admin_uid": admin_uid,
        "member_uids": member_uids,
        "created_at": datetime.utcnow().isoformat(),
        "last_message": None,
        "last_message_time": None
    }
    firestore_client.collection("groups").document(group_id).set(group_doc)
    return {"message": "Group created", "group_id": group_id}

class SendGroupMessageRequest(BaseModel):
    group_id: str
    message_content: str
    message_type: str = "text"  # text, image, file
    file_url: Optional[str] = None

@app.post("/messagessendgroup")
def send_group_message(
    data: SendGroupMessageRequest,
    current_user: dict = Depends(get_current_user)
):
    sender_uid = current_user["uid"]
    group_doc = firestore_client.collection("groups").document(data.group_id).get()
    if not group_doc.exists:
        raise HTTPException(status_code=404, detail="Group not found")
    group_data = group_doc.to_dict()
    if sender_uid not in group_data["member_uids"]:
        raise HTTPException(status_code=403, detail="You are not a member of this group")
    messages_ref = firestore_client.collection("groups").document(data.group_id).collection("messages")
    message_data = {
        "sender_uid": sender_uid,
        "group_id": data.group_id,
        "message_content": data.message_content,
        "timestamp": datetime.utcnow().isoformat(),
        "message_type": data.message_type,
        "file_url": data.file_url
    }
    msg_ref = messages_ref.document()
    msg_ref.set(message_data)
    # Cập nhật last_message, last_message_time ở document group
    firestore_client.collection("groups").document(data.group_id).update({
        "last_message": data.message_content,
        "last_message_time": message_data["timestamp"]
    })
    return {"message": "Group message sent", "message_id": msg_ref.id}

@app.get("/messagesgroup/{group_id}")
def get_group_messages(
    group_id: str = Path(...),
    current_user: dict = Depends(get_current_user)
):
    uid = current_user["uid"]
    group_doc = firestore_client.collection("groups").document(group_id).get()
    if not group_doc.exists:
        raise HTTPException(status_code=404, detail="Group not found")
    group_data = group_doc.to_dict()
    if uid not in group_data["member_uids"]:
        raise HTTPException(status_code=403, detail="You are not a member of this group")
    messages_ref = firestore_client.collection("groups").document(group_id).collection("messages")
    messages = []
    for doc in messages_ref.order_by("timestamp").stream():
        msg = doc.to_dict()
        msg["id"] = doc.id
        # Lấy display_name của sender
        sender_uid = msg.get("sender_uid")
        if sender_uid:
            user_doc = firestore_client.collection("users").document(sender_uid).get()
            if user_doc.exists:
                user_data = user_doc.to_dict()
                msg["sender_display_name"] = user_data.get("display_name", sender_uid)
            else:
                msg["sender_display_name"] = sender_uid
        messages.append(msg)
    return {"messages": messages}

@app.post("/uploadfile")
async def upload_file(
    file: UploadFile = File(...),
    current_user: dict = Depends(get_current_user)
):
    try:
        uid = current_user["uid"]
        logger.info(f"Upload request from user {uid} for file: {file.filename}")
        
        # Read file content
        content = await file.read()
        if len(content) == 0:
            raise HTTPException(status_code=400, detail="Empty file")
        
        if len(content) > 5 * 1024 * 1024:  # 5MB limit for Realtime DB
            raise HTTPException(status_code=400, detail="File too large. Maximum size is 5MB for Realtime Database")
        
        # Convert to base64 for storage in Realtime Database
        import base64
        file_base64 = base64.b64encode(content).decode('utf-8')
        
        # Generate unique filename
        timestamp = datetime.utcnow().strftime("%Y%m%d_%H%M%S")
        file_extension = os.path.splitext(file.filename)[1] if file.filename else ".jpg"
        unique_filename = f"avatar_{uid}_{timestamp}{file_extension}"
        
        # Store in Realtime Database - ONLY update avatar section
        avatar_data = {
            "uid": uid,
            "filename": unique_filename,
            "original_name": file.filename,
            "content_type": file.content_type or "image/jpeg",
            "size": len(content),
            "uploaded_at": datetime.utcnow().isoformat(),
            "data": file_base64  # Base64 encoded image data
        }
        
        # Save to Realtime Database under users/{uid}/avatar - use update, not set
        avatar_ref = db.reference(f'users/{uid}/avatar')
        avatar_ref.update(avatar_data)
        logger.info(f"Avatar saved to Realtime Database for user: {uid}")
        
        # Also save metadata to Firestore for backup/search
        try:
            metadata = {
                "uid": uid,
                "file_name": unique_filename,
                "original_name": file.filename,
                "content_type": file.content_type or "image/jpeg",
                "size": len(content),
                "uploaded_at": datetime.utcnow().isoformat(),
                "storage_type": "realtime_database"
            }
            firestore_client.collection("user_avatars").document(uid).set(metadata)
            logger.info(f"Metadata saved to Firestore for user: {uid}")
        except Exception as e:
            logger.warning(f"Failed to save metadata to Firestore: {e}")
        
        return {
            "file_url": f"realtime_db://users/{uid}/avatar",  # Custom URL scheme
            "filename": unique_filename,
            "size": len(content),
            "message": "Avatar uploaded to Realtime Database successfully",
            "storage_type": "realtime_database"
        }
        
    except HTTPException:
        # Re-raise HTTP exceptions
        raise
    except Exception as e:
        logger.error(f"Unexpected error in upload_file: {e}")
        raise HTTPException(
            status_code=500, 
            detail="Internal server error during file upload. Please try again."
        )

from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Depends, HTTPException, status, Path
from pydantic import BaseModel
from datetime import datetime
from firebase_admin import auth
from collections import defaultdict

# Multiple WebSocket connections per user
active_rooms = defaultdict(list)

# ===== Model =====
class CallOfferRequest(BaseModel):
    target_uid: str
    offer: dict

class CallAnswerRequest(BaseModel):
    call_id: str
    answer: dict

class CallICECandidateRequest(BaseModel):
    call_id: str
    candidate: dict

class CallRejectRequest(BaseModel):
    call_id: str
class CallCancelRequest(BaseModel):
    call_id: str

# ===== Helper =====
def make_room_id(uid1, uid2):
    return "_".join(sorted([uid1, uid2]))

async def verify_ws_token(websocket: WebSocket):
    token = websocket.headers.get("authorization")
    if not token or not token.startswith("Bearer "):
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return None
    id_token = token.split("Bearer ")[1]
    try:
        # Allow small clock skew on first login
        decoded = auth.verify_id_token(id_token, clock_skew_seconds=60)
        return decoded["uid"]
    except:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return None

# ===== REST Endpoints =====
@app.post("/call/offer")
async def call_offer(data: CallOfferRequest, current_user: dict = Depends(get_current_user)):
    caller_uid = current_user["uid"]
    call_id = make_room_id(caller_uid, data.target_uid)

    firestore_client.collection("calls").document(call_id).set({
        "offer": data.offer,
        "caller_uid": caller_uid,
        "callee_uid": data.target_uid,
        "created_at": datetime.utcnow().isoformat(),
        "status": "offer_sent"
    }, merge=True)

    # Push incoming call notification to callee if online via WS notify
    ws = active_connections.get(data.target_uid)
    if ws is not None:
        try:
            await ws.send_json({
                "type": "incoming_call",
                "call_id": call_id,
                "caller_uid": caller_uid
            })
        except Exception:
            # Ignore notify failure; callee can still poll /call/incoming
            pass

    return {"message": "Offer sent", "call_id": call_id}

@app.post("/call/answer")
def call_answer(data: CallAnswerRequest, current_user: dict = Depends(get_current_user)):
    answerer_uid = current_user["uid"]

    firestore_client.collection("calls").document(data.call_id).set({
        "answer": data.answer,
        "answerer_uid": answerer_uid,
        "answered_at": datetime.utcnow().isoformat(),
        "status": "answered"
    }, merge=True)

    return {"message": "Answer sent", "call_id": data.call_id}

@app.post("/call/reject")
async def call_reject(data: CallRejectRequest, current_user: dict = Depends(get_current_user)):
    my_uid = current_user["uid"]
    call_ref = firestore_client.collection("calls").document(data.call_id)
    doc = call_ref.get()
    if not doc.exists:
        raise HTTPException(status_code=404, detail="Call not found")
    call = doc.to_dict()
    if my_uid not in [call.get("caller_uid"), call.get("callee_uid")]:
        raise HTTPException(status_code=403, detail="Not participant")

    call_ref.set({
        "status": "rejected",
        "rejected_by": my_uid,
        "rejected_at": datetime.utcnow().isoformat()
    }, merge=True)

    # Notify caller via notify WS if connected
    caller_uid = call.get("caller_uid")
    ws = active_connections.get(caller_uid)
    if ws is not None:
        try:
            await ws.send_json({
                "type": "call_rejected",
                "call_id": data.call_id,
                "rejected_by": my_uid
            })
        except Exception:
            pass

    return {"message": "Call rejected", "call_id": data.call_id}

@app.post("/call/cancel")
async def call_cancel(data: CallCancelRequest, current_user: dict = Depends(get_current_user)):
    caller_uid = current_user["uid"]
    call_ref = firestore_client.collection("calls").document(data.call_id)
    doc = call_ref.get()
    if not doc.exists:
        raise HTTPException(status_code=404, detail="Call not found")
    call = doc.to_dict()
    if call.get("caller_uid") != caller_uid:
        raise HTTPException(status_code=403, detail="Only caller can cancel")
    # Update status to canceled only if not answered yet
    new_status = "canceled" if call.get("status") == "offer_sent" else call.get("status", "canceled")
    call_ref.set({
        "status": new_status,
        "canceled_by": caller_uid,
        "canceled_at": datetime.utcnow().isoformat()
    }, merge=True)

    # Notify callee via notify WS if connected
    callee_uid = call.get("callee_uid")
    ws = active_connections.get(callee_uid)
    if ws is not None:
        try:
            await ws.send_json({
                "type": "call_canceled",
                "call_id": data.call_id,
                "caller_uid": caller_uid
            })
        except Exception:
            pass

    return {"message": "Call canceled", "call_id": data.call_id, "status": new_status}

@app.get("/call/info/{call_id}")
def get_call_info(call_id: str, current_user: dict = Depends(get_current_user)):
    doc = firestore_client.collection("calls").document(call_id).get()
    if not doc.exists:
        raise HTTPException(status_code=404, detail="Call not found")
    data = doc.to_dict()
    data["call_id"] = call_id
    return data

@app.get("/call/incoming")
def calls_incoming(current_user: dict = Depends(get_current_user)):
    my_uid = current_user["uid"]
    q = firestore_client.collection("calls") \
        .where("callee_uid", "==", my_uid) \
        .where("status", "==", "offer_sent") \
        .stream()
    return {"calls": [{**d.to_dict(), "call_id": d.id} for d in q]}

# ===== WebSocket Signaling =====
@app.websocket("/ws/call/{room_id}")
async def websocket_call(websocket: WebSocket, room_id: str):
    uid = await verify_ws_token(websocket)
    if not uid:
        return

    await websocket.accept()
    active_rooms[room_id].append({"uid": uid, "ws": websocket})
    print(f"[WebSocket] {uid} joined {room_id}")

    try:
        while True:
            data = await websocket.receive_json()
            event_type = data.get("type")

            # Gửi cho tất cả trừ chính mình
            for client in active_rooms[room_id]:
                if client["uid"] != uid:
                    await client["ws"].send_json({"from_uid": uid, **data})

            # Fallback lưu ICE nếu target chưa online
            if event_type == "candidate" and len(active_rooms[room_id]) < 2:
                firestore_client.collection("calls").document(room_id) \
                    .collection("ice_candidates").add({
                        "candidate": data["candidate"],
                        "sender_uid": uid,
                        "timestamp": datetime.utcnow().isoformat()
                    })

    except WebSocketDisconnect:
        active_rooms[room_id] = [c for c in active_rooms[room_id] if c["ws"] != websocket]
        if not active_rooms[room_id]:
            del active_rooms[room_id]
        print(f"[WebSocket] {uid} left {room_id}")
    except Exception as e:
        active_rooms[room_id] = [c for c in active_rooms[room_id] if c["ws"] != websocket]
        await websocket.close()
        print(f"[WebSocket][ERROR] {uid} in {room_id}: {e}")

# ===== User Notification WebSocket =====
@app.websocket("/ws/notify")
async def websocket_notify(websocket: WebSocket):
    uid = await verify_ws_token(websocket)
    if not uid:
        return
    await websocket.accept()
    # store/replace latest connection for this uid
    active_connections[uid] = websocket
    print(f"[NotifyWS] {uid} connected")
    try:
        while True:
            # Keep the connection alive; ignore incoming messages from client
            await websocket.receive_text()
    except WebSocketDisconnect:
        if active_connections.get(uid) is websocket:
            del active_connections[uid]
        print(f"[NotifyWS] {uid} disconnected")
    except Exception:
        if active_connections.get(uid) is websocket:
            del active_connections[uid]
        try:
            await websocket.close()
        except Exception:
            pass

# ==== Friend Request APIs ====
from fastapi import Body

class FriendRequestModel(BaseModel):
    to_uid: str

class FriendRequestActionModel(BaseModel):
    request_id: str

@app.post("/friendrequest/send")
def send_friend_request(data: FriendRequestModel, current_user: dict = Depends(get_current_user)):
    from_uid = current_user["uid"]
    to_uid = data.to_uid
    if from_uid == to_uid:
        raise HTTPException(status_code=400, detail="Cannot send request to yourself")
    # Kiểm tra đã là bạn bè chưa
    friend_ref = firestore_client.collection("users").document(from_uid).collection("friends").document(to_uid)
    if friend_ref.get().exists:
        raise HTTPException(status_code=400, detail="Already friends")
    # Kiểm tra đã gửi request chưa
    reqs = firestore_client.collection("friend_requests")\
        .where("from_uid", "==", from_uid)\
        .where("to_uid", "==", to_uid)\
        .where("status", "==", "pending").stream()
    if any(reqs):
        raise HTTPException(status_code=400, detail="Request already sent")
    # Tạo request mới
    firestore_client.collection("friend_requests").add({
        "from_uid": from_uid,
        "to_uid": to_uid,
        "status": "pending",
        "created_at": datetime.utcnow().isoformat()
    })
    return {"message": "Friend request sent"}

@app.get("/friendrequest/incoming")
def get_incoming_requests(current_user: dict = Depends(get_current_user)):
    my_uid = current_user["uid"]
    reqs = firestore_client.collection("friend_requests")\
        .where("to_uid", "==", my_uid)\
        .where("status", "==", "pending").stream()
    result = []
    for req in reqs:
        data = req.to_dict()
        data["id"] = req.id
        # Lấy thêm display_name của người gửi
        from_uid = data.get("from_uid")
        if from_uid:
            user_doc = firestore_client.collection("users").document(from_uid).get()
            if user_doc.exists:
                user_data = user_doc.to_dict()
                data["from_display_name"] = user_data.get("display_name", "")
        result.append(data)
    return {"requests": result}

@app.post("/friendrequest/accept")
def accept_friend_request(data: FriendRequestActionModel, current_user: dict = Depends(get_current_user)):
    my_uid = current_user["uid"]
    req_ref = firestore_client.collection("friend_requests").document(data.request_id)
    req_doc = req_ref.get()
    if not req_doc.exists:
        raise HTTPException(status_code=404, detail="Request not found")
    req = req_doc.to_dict()
    if req["to_uid"] != my_uid or req["status"] != "pending":
        raise HTTPException(status_code=400, detail="Invalid request")
    from_uid = req["from_uid"]
    # Thêm bạn bè 2 chiều Firestore
    firestore_client.collection("users").document(my_uid).collection("friends").document(from_uid).set({
        "friend_uid": from_uid,
        "added_at": datetime.utcnow().isoformat()
    })
    firestore_client.collection("users").document(from_uid).collection("friends").document(my_uid).set({
        "friend_uid": my_uid,
        "added_at": datetime.utcnow().isoformat()
    })
    # Lưu vào Realtime Database
    db.reference(f'friends/{my_uid}/{from_uid}').set({"friend_uid": from_uid, "added_at": datetime.utcnow().isoformat()})
    db.reference(f'friends/{from_uid}/{my_uid}').set({"friend_uid": my_uid, "added_at": datetime.utcnow().isoformat()})

    # Tạo document private_chats rỗng nếu chưa có
    chat_id = "_".join(sorted([my_uid, from_uid]))
    private_chat_ref = firestore_client.collection("private_chats").document(chat_id)
    if not private_chat_ref.get().exists:
        private_chat_ref.set({
            "participants": [my_uid, from_uid],
            "last_message": "",
            "last_message_time": "",
            "created_at": datetime.utcnow().isoformat()
        })

    # Cập nhật trạng thái request
    req_ref.update({"status": "accepted"})
    return {"message": "Friend request accepted"}

@app.post("/friendrequest/reject")
def reject_friend_request(data: FriendRequestActionModel, current_user: dict = Depends(get_current_user)):
    my_uid = current_user["uid"]
    req_ref = firestore_client.collection("friend_requests").document(data.request_id)
    req_doc = req_ref.get()
    if not req_doc.exists:
        raise HTTPException(status_code=404, detail="Request not found")
    req = req_doc.to_dict()
    if req["to_uid"] != my_uid or req["status"] != "pending":
        raise HTTPException(status_code=400, detail="Invalid request")
    req_ref.update({"status": "rejected"})
    return {"message": "Friend request rejected"}

@app.get("/finduserbyemail")
def find_user_by_email(email: str = Query(...), current_user: dict = Depends(get_current_user)):
    users_ref = firestore_client.collection("users")
    query = users_ref.where("email", "==", email).limit(1).stream()
    for doc in query:
        user = doc.to_dict()
        return user
    raise HTTPException(status_code=404, detail="User not found")

@app.get("/finduserbydisplayname")
def find_user_by_displayname(display_name: str = Query(...), current_user: dict = Depends(get_current_user)):
    users_ref = firestore_client.collection("users")
    query = users_ref.where("display_name", "==", display_name).limit(1).stream()
    for doc in query:
        user = doc.to_dict()
        return user
    raise HTTPException(status_code=404, detail="User not found")

class UpdateUserRequest(BaseModel):
    display_name: Optional[str] = None
    email: Optional[EmailStr] = None
    password: Optional[str] = None
    avatar_url: Optional[str] = None

@app.post("/update_user")
def update_user(data: UpdateUserRequest, current_user: dict = Depends(get_current_user)):
    uid = current_user["uid"]
    
    # Prepare update data
    update_data = {}
    if data.display_name is not None:
        update_data["display_name"] = data.display_name
    if data.email is not None:
        update_data["email"] = data.email
    if data.avatar_url is not None:
        update_data["avatar_url"] = data.avatar_url
    
    if not update_data:
        raise HTTPException(status_code=400, detail="No fields to update")
    
    try:
        # Update Firestore - use update to preserve existing data
        firestore_client.collection("users").document(uid).update(update_data)
        
        # Update Realtime Database - use update to preserve existing data
        user_ref = db.reference(f'users/{uid}')
        user_ref.update(update_data)
        
        logger.info(f"User {uid} updated successfully with fields: {list(update_data.keys())}")
        return {"message": "User updated successfully", "updated_fields": list(update_data.keys())}
        
    except Exception as e:
        logger.error(f"Failed to update user {uid}: {e}")
        raise HTTPException(status_code=500, detail="Failed to update user")

class ChangePasswordRequest(BaseModel):
    new_password: str

@app.post("/change_password")
def change_password(data: ChangePasswordRequest, current_user: dict = Depends(get_current_user)):
    uid = current_user["uid"]
    try:
        auth.update_user(uid, password=data.new_password)
        # Optionally, mark user doc updated
        firestore_client.collection("users").document(uid).set({
            "password_updated_at": datetime.utcnow().isoformat()
        }, merge=True)
        return {"message": "Password updated"}
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))

app.include_router(router) 

@app.get("/chatlist")
def get_chat_list(current_user: dict = Depends(get_current_user)):
    uid = current_user["uid"]
    chat_list = []

    # Lấy danh sách bạn bè từ subcollection friends
    friends_ref = firestore_client.collection("users").document(uid).collection("friends")
    friends_docs = friends_ref.stream()
    for doc in friends_docs:
        friend_uid = doc.id
        friend_doc = firestore_client.collection("users").document(friend_uid).get()
        friend_name = friend_doc.to_dict().get("display_name", "") if friend_doc.exists else ""
        # Lấy last message nếu có
        chat_id = "_".join(sorted([uid, friend_uid]))
        private_chat_doc = firestore_client.collection("private_chats").document(chat_id).get()
        last_message = ""
        if private_chat_doc.exists:
            last_message = private_chat_doc.to_dict().get("last_message", "")
        chat_list.append({
            "id": friend_uid,
            "displayName": friend_name,
            "lastMessage": last_message,
            "isGroup": False
        })

    # Group chats như cũ
    groups_ref = firestore_client.collection("groups")
    groups = groups_ref.where("member_uids", "array_contains", uid).stream()
    for doc in groups:
        data = doc.to_dict()
        chat_list.append({
            "id": data["group_id"],
            "displayName": data["group_name"],
            "lastMessage": data.get("last_message", ""),
            "isGroup": True
        })

    return {"chats": chat_list} 

@app.get("/avatar/{uid}")
async def get_user_avatar(uid: str):
    """Get user avatar from Realtime Database - public access"""
    try:
        avatar_ref = db.reference(f'users/{uid}/avatar')
        avatar_data = avatar_ref.get()

        if not avatar_data:
            # Log more details for debugging
            logger.warning(f"Avatar not found for user {uid} in Realtime Database")
            
            # Check if user exists in Realtime Database
            user_ref = db.reference(f'users/{uid}')
            user_data = user_ref.get()
            if user_data:
                logger.info(f"User {uid} exists in Realtime Database with fields: {list(user_data.keys())}")
            else:
                logger.warning(f"User {uid} not found in Realtime Database at all")
            
            # Check if avatar exists in Firestore
            try:
                avatar_metadata = firestore_client.collection("user_avatars").document(uid).get()
                if avatar_metadata.exists:
                    logger.info(f"Avatar metadata exists in Firestore for user {uid}")
                else:
                    logger.warning(f"No avatar metadata in Firestore for user {uid}")
            except Exception as e:
                logger.error(f"Error checking Firestore avatar metadata: {e}")
            
            raise HTTPException(status_code=404, detail="Avatar not found")

        import base64
        try:
            image_bytes = base64.b64decode(avatar_data['data'])
        except Exception as e:
            logger.error(f"Failed to decode base64 image for user {uid}: {e}")
            raise HTTPException(status_code=500, detail="Invalid image data")

        from fastapi.responses import Response
        content_type = avatar_data.get('content_type', 'image/jpeg')

        return Response(
            content=image_bytes,
            media_type=content_type,
            headers={
                "Content-Disposition": f"inline; filename={avatar_data.get('filename', 'avatar.jpg')}",
                "Cache-Control": "public, max-age=3600"
            }
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error getting avatar for user {uid}: {e}")
        raise HTTPException(status_code=500, detail="Failed to retrieve avatar")


@app.get("/avatar/{uid}/info")
async def get_user_avatar_info(uid: str, current_user: dict = Depends(get_current_user)):
    """Get user avatar metadata from Realtime Database"""
    try:
        avatar_ref = db.reference(f'users/{uid}/avatar')
        avatar_data = avatar_ref.get()
        
        if not avatar_data:
            raise HTTPException(status_code=404, detail="Avatar not found")
        
        # Return metadata without the actual image data
        metadata = {
            "uid": avatar_data.get("uid"),
            "filename": avatar_data.get("filename"),
            "original_name": avatar_data.get("original_name"),
            "content_type": avatar_data.get("content_type"),
            "size": avatar_data.get("size"),
            "uploaded_at": avatar_data.get("uploaded_at"),
            "has_data": bool(avatar_data.get("data"))
        }
        
        return metadata
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error getting avatar info for user {uid}: {e}")
        raise HTTPException(status_code=500, detail="Failed to retrieve avatar info")

@app.get("/debug/avatar/{uid}")
async def debug_user_avatar(uid: str):
    """Debug endpoint to check avatar status for any user (public access)"""
    try:
        result = {
            "uid": uid,
            "realtime_database": {},
            "firestore": {},
            "summary": {}
        }
        
        # Check Realtime Database
        try:
            user_ref = db.reference(f'users/{uid}')
            user_data = user_ref.get()
            if user_data:
                result["realtime_database"]["user_exists"] = True
                result["realtime_database"]["user_fields"] = list(user_data.keys())
                
                # Check avatar specifically
                avatar_ref = db.reference(f'users/{uid}/avatar')
                avatar_data = avatar_ref.get()
                if avatar_data:
                    result["realtime_database"]["avatar_exists"] = True
                    result["realtime_database"]["avatar_fields"] = list(avatar_data.keys())
                    result["realtime_database"]["has_image_data"] = bool(avatar_data.get("data"))
                else:
                    result["realtime_database"]["avatar_exists"] = False
            else:
                result["realtime_database"]["user_exists"] = False
        except Exception as e:
            result["realtime_database"]["error"] = str(e)
        
        # Check Firestore
        try:
            user_doc = firestore_client.collection("users").document(uid).get()
            if user_doc.exists:
                result["firestore"]["user_exists"] = True
                user_data = user_doc.to_dict()
                result["firestore"]["user_fields"] = list(user_data.keys())
                
                # Check avatar metadata
                avatar_metadata = firestore_client.collection("user_avatars").document(uid).get()
                if avatar_metadata.exists:
                    result["firestore"]["avatar_metadata_exists"] = True
                    metadata = avatar_metadata.to_dict()
                    result["firestore"]["avatar_metadata_fields"] = list(metadata.keys())
                else:
                    result["firestore"]["avatar_metadata_exists"] = False
            else:
                result["firestore"]["user_exists"] = False
        except Exception as e:
            result["firestore"]["error"] = str(e)
        
        # Summary
        result["summary"]["has_avatar_in_realtime"] = result["realtime_database"].get("avatar_exists", False)
        result["summary"]["has_avatar_metadata_in_firestore"] = result["firestore"].get("avatar_metadata_exists", False)
        result["summary"]["avatar_accessible"] = result["realtime_database"].get("avatar_exists", False) and result["realtime_database"].get("has_image_data", False)
        
        return result
        
    except Exception as e:
        logger.error(f"Error in debug_user_avatar for {uid}: {e}")
        return {"error": str(e), "uid": uid} 

@app.get("/debug/avatars")
async def debug_all_avatars():
    """Debug endpoint to check all avatars in the system (public access)"""
    try:
        result = {
            "total_users_with_avatars": 0,
            "users_with_avatars": [],
            "total_users": 0,
            "users_without_avatars": []
        }
        
        # Get all users from Firestore
        users_ref = firestore_client.collection("users")
        users = users_ref.stream()
        
        for user_doc in users:
            uid = user_doc.id
            result["total_users"] += 1
            
            # Check if user has avatar in Realtime Database
            try:
                avatar_ref = db.reference(f'users/{uid}/avatar')
                avatar_data = avatar_ref.get()
                
                if avatar_data and avatar_data.get("data"):
                    result["total_users_with_avatars"] += 1
                    result["users_with_avatars"].append({
                        "uid": uid,
                        "display_name": user_doc.to_dict().get("display_name", ""),
                        "avatar_fields": list(avatar_data.keys()),
                        "has_image_data": bool(avatar_data.get("data"))
                    })
                else:
                    result["users_without_avatars"].append({
                        "uid": uid,
                        "display_name": user_doc.to_dict().get("display_name", "")
                    })
            except Exception as e:
                result["users_without_avatars"].append({
                    "uid": uid,
                    "display_name": user_doc.to_dict().get("display_name", ""),
                    "error": str(e)
                })
        
        return result
        
    except Exception as e:
        logger.error(f"Error in debug_all_avatars: {e}")
        return {"error": str(e)}

@app.post("/debug/restore_avatar/{uid}")
async def debug_restore_avatar(uid: str):
    """Debug endpoint to attempt avatar restoration for a specific user (public access)"""
    try:
        result = {
            "uid": uid,
            "actions_taken": [],
            "status": "unknown"
        }
        
        # Check if avatar exists in Realtime Database
        avatar_ref = db.reference(f'users/{uid}/avatar')
        avatar_data = avatar_ref.get()
        
        if avatar_data and avatar_data.get("data"):
            result["status"] = "avatar_already_exists"
            result["actions_taken"].append("Avatar already exists in Realtime Database")
            return result
        
        # Check if avatar metadata exists in Firestore
        avatar_metadata = firestore_client.collection("user_avatars").document(uid).get()
        if not avatar_metadata.exists:
            result["status"] = "no_avatar_metadata"
            result["actions_taken"].append("No avatar metadata found in Firestore")
            return result
        
        # Try to restore avatar data
        try:
            metadata = avatar_metadata.to_dict()
            if metadata.get("storage_type") == "realtime_database":
                # This suggests the avatar should be in Realtime Database but isn't
                result["status"] = "metadata_exists_but_avatar_missing"
                result["actions_taken"].append("Avatar metadata exists but actual avatar data is missing")
                result["actions_taken"].append("This indicates a data inconsistency issue")
            else:
                result["status"] = "metadata_exists_but_wrong_storage_type"
                result["actions_taken"].append(f"Avatar metadata exists but storage type is: {metadata.get('storage_type')}")
        except Exception as e:
            result["status"] = "error_reading_metadata"
            result["actions_taken"].append(f"Error reading metadata: {str(e)}")
        
        return result
        
    except Exception as e:
        logger.error(f"Error in debug_restore_avatar for {uid}: {e}")
        return {"error": str(e), "uid": uid}

@app.post("/restore_user_data")
def restore_user_data(current_user: dict = Depends(get_current_user)):
    """Restore user data from Firestore to Realtime Database if needed"""
    uid = current_user["uid"]
    
    try:
        # Get user data from Firestore
        user_doc = firestore_client.collection("users").document(uid).get()
        if not user_doc.exists:
            raise HTTPException(status_code=404, detail="User not found in Firestore")
        
        user_data = user_doc.to_dict()
        
        # Get existing avatar data from Realtime Database if available
        avatar_ref = db.reference(f'users/{uid}/avatar')
        existing_avatar = avatar_ref.get()
        
        # Merge user data with existing avatar
        if existing_avatar:
            user_data["avatar"] = existing_avatar
        
        # Restore to Realtime Database
        user_ref = db.reference(f'users/{uid}')
        user_ref.set(user_data)
        
        logger.info(f"User data restored for {uid} from Firestore")
        return {"message": "User data restored successfully", "restored_fields": list(user_data.keys())}
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to restore user data for {uid}: {e}")
        raise HTTPException(status_code=500, detail="Failed to restore user data")

@app.get("/user_data_status")
def get_user_data_status(current_user: dict = Depends(get_current_user)):
    """Check if user data exists in both Firestore and Realtime Database"""
    uid = current_user["uid"]
    
    try:
        # Check Firestore
        firestore_doc = firestore_client.collection("users").document(uid).get()
        firestore_exists = firestore_doc.exists
        firestore_data = firestore_doc.to_dict() if firestore_exists else {}
        
        # Check Realtime Database
        realtime_ref = db.reference(f'users/{uid}')
        realtime_data = realtime_ref.get()
        realtime_exists = realtime_data is not None
        
        # Check avatar data
        avatar_ref = db.reference(f'users/{uid}/avatar')
        avatar_data = avatar_ref.get()
        avatar_exists = avatar_data is not None
        
        return {
            "uid": uid,
            "firestore": {
                "exists": firestore_exists,
                "fields": list(firestore_data.keys()) if firestore_exists else []
            },
            "realtime_database": {
                "exists": realtime_exists,
                "fields": list(realtime_data.keys()) if realtime_exists else []
            },
            "avatar": {
                "exists": avatar_exists,
                "fields": list(avatar_data.keys()) if avatar_exists else []
            }
        }
        
    except Exception as e:
        logger.error(f"Failed to check user data status for {uid}: {e}")
        raise HTTPException(status_code=500, detail="Failed to check user data status") 

@app.get("/user/{uid}")
async def get_user_by_uid(uid: str, current_user: dict = Depends(get_current_user)):
    """Get user information by UID for chat list display"""
    try:
        # Get user data from Firestore
        user_doc = firestore_client.collection("users").document(uid).get()
        if not user_doc.exists:
            raise HTTPException(status_code=404, detail="User not found")
        
        user_data = user_doc.to_dict()
        
        # Get avatar info from Realtime Database
        avatar_ref = db.reference(f'users/{uid}/avatar')
        avatar_data = avatar_ref.get()
        
        # Prepare response
        response_data = {
            "uid": user_data.get("uid"),
            "display_name": user_data.get("display_name"),
            "email": user_data.get("email"),
            "avatar_url": user_data.get("avatar_url", ""),
            "created_at": user_data.get("created_at"),
            "has_avatar": avatar_data is not None
        }
        
        return response_data
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error getting user {uid}: {e}")
        raise HTTPException(status_code=500, detail="Failed to retrieve user information") 
