import requests

BASE_URL = "http://127.0.0.1:8000"
email = "phu142@gmail.com"
password = "142142"

def test_login():
    # Đăng nhập backend để lấy idToken
    data = {
        "email": email,
        "password": password
    }
    resp = requests.post(f"{BASE_URL}/login", json=data)
    print("Login response:", resp.status_code, resp.json())
    if resp.status_code == 200 and "idToken" in resp.json():
        id_token = resp.json()["idToken"]
        # Gửi idToken tới backend để xác thực
        verify_resp = requests.post(f"{BASE_URL}/verify-token", json={"id_token": id_token})
        print("Verify token response:", verify_resp.status_code, verify_resp.json())
    else:
        print("Không lấy được idToken!")

if __name__ == "__main__":
    test_login() 