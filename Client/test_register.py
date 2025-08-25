import requests

BASE_URL = "http://192.168.1.11:8000"  # Đổi thành địa chỉ server của bạn

def register(email, password, display_name=None):
    url = f"{BASE_URL}/authregister"
    data = {
        "email": email,
        "password": password,
        "display_name": display_name or email.split("@")[0]
    }
    try:
        response = requests.post(url, json=data)
        if response.status_code == 200:
            print("Đăng ký thành công:", response.json())
        else:
            print("Đăng ký thất bại:", response.text)
    except Exception as e:
        print("Lỗi:", e)

# Ví dụ sử dụng:
register("testuser@example.com", "yourpassword", "Test User")