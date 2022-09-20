# KAHEB 1.4.0
### Kide.app Async HTTP Event Bot v1.4.0


**Authentication tag:**

Insert Kide.app bearer authentication tag into user.txt and ensure it is in the same directory where you run KAHEB.exe.

**How to get (Firefox):**

1. Log in to https://kide.app/ on Firefox
2. Open the developer console (F12)
3. Select _Network_ from the left, then _XHR_ from the right
4. Reload the page, on the left you will see a list of requests
5. Search for the one with _Domain_: _api.kide.app_, _File_: _user_ and _Type_: _json_
6. Click on the request, and from the right select _Headers_
7. Scroll down to _Request headers_ and look for _Authorization_
8. Copy the entire value field of that header, including "Bearer"
9. Paste to user.txt

**TODO: How to get (Chrome):**