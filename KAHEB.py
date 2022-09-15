import asyncio
from aiohttp import ClientSession
from aiohttp import TCPConnector
from aiohttp import ContentTypeError
import time

"""
Kide.app Async HTTP Event Bot (KAHEB)
@author: Vertti Nuotio
@version: 1.1
"""

AUTH_URL = "https://api.kide.app/api/authentication/user"
GET_URL = "https://api.kide.app/api/products/"
POST_URL = "https://api.kide.app/api/reservations"
REQUEST_TIMEOUT = 10  # Timeout parameter for all aiohttp requests, seconds


async def validate_user(session, user):
    """
    Validates user authentication string against Kide.app API
    :param session: ClientSession to connect through
    :param user: Kide.app user authentication string ("Bearer ...")
    :return: Response status code
    """
    headers = \
        {
            "Accept": "*/*",
            "Accept-Language": "*",
            "Accept-Encoding": "gzip",
            "Content-Type": "application/json;charset=utf-8",
            "Connection": "keep-alive",
            "TE": "trailers",
            "Authorization": user
        }
    res = await session.get(AUTH_URL, headers=headers, timeout=REQUEST_TIMEOUT)
    return res.status


async def postrequest(session, iid, qty, user):
    """
    Makes a POST request to the ticket reservation API using Kide.app's
    inventory ID for the ticket

    :param session: ClientSession to connect through
    :param iid: Wanted ticket's Kide.app API inventory ID
    :param qty: Quantity of tickets to reserve
    :param user: Kide.app user authentication string ("Bearer ...")
    """
    headers = \
        {
            "Accept": "*/*",
            "Accept-Language": "*",
            "Accept-Encoding": "gzip",
            "Content-Type": "application/json;charset=utf-8",
            "Connection": "keep-alive",
            "TE": "trailers",
            "Authorization": user
        }
    data = \
        {
            "toCreate":
                [{
                    "inventoryId": iid,
                    "quantity": qty
                }],
        }

    await session.post(POST_URL, headers=headers, json=data, timeout=REQUEST_TIMEOUT)


async def loop_postrequest(session, iid, qty, user):
    """
    Makes asynchronous POST requests to reserve a ticket with given inventory ID twice

    :param session: ClientSession to connect through
    :param iid: Wanted ticket's Kide.app API inventory ID
    :param qty: Quantity of tickets to reserve
    :param user: Kide.app user authentication string ("Bearer ...")
    """
    posts = []
    for i in range(2):
        post = asyncio.ensure_future(postrequest(session, iid, qty, user))
        posts.append(post)

    await asyncio.gather(*posts, return_exceptions=True)


async def getrequest(session, eid, flag=None):
    """
    Makes a GET request to the product catalogue API of Kide.app and returns
    the JSON data of the wanted event

    :param session: ClientSession to connect through
    :param eid: Wanted event's event ID, available in the address bar
    :param flag: When the ticket inventory ID's are found, this flag is set to stop any further GET requests.
    If none is provided, assume connection testing/validation mode and ignore it
    :return: JSON data of the page, which includes the tickets and their data, if sales have been opened,
    otherwise an empty list
    """
    url = GET_URL + eid
    async with session.get(url, timeout=REQUEST_TIMEOUT) as res:
        try:
            data = await res.json()
        except ContentTypeError as err:
            raise err
        tickets = data["model"]["variants"]
        if tickets:
            if flag is not None:
                flag.set()
        return tickets


async def loop_getrequest(session, eid, timeout, start):
    """
    Makes asynchronous GET requests to Kide.app API until a request returns with ticket data

    :param session: ClientSession to connect through
    :param eid: Wanted event's event ID, available in the address bar
    :param timeout: how many seconds to loop for before exiting
    :param start: the initialization moment as time.time() of this method
    :return: JSON data of the page, which includes the tickets and their data, if sales have been opened,
    otherwise an empty list
    """
    flag = asyncio.Event()
    while True:
        tickets_raw = asyncio.ensure_future(getrequest(session, eid, flag))
        await tickets_raw

        if flag.is_set():  # Tickets found
            break

        time_diff = time.time() - start
        if time_diff > timeout:
            print("GET requests timed out, quitting...")
            break
    return tickets_raw.result()


def read_user_file():
    """
    Reads the file "user.txt" containing the Kide.app authentication string.
    Should be located in the same folder as the executable
    :return: User authentication string if succesfully read, raises a FileNotFoundError otherwise
    """
    try:
        with open("user.txt", mode="r") as file:
            user = file.read()
            return user
    except FileNotFoundError as err:
        print("FileNotFoundError: user.txt not found!")
        raise err


async def validate_eid(session, eid):
    """
    Validates the event ID by making a GET request from the Kide.app API.
    Raises ContentTypeError if request fails (most likely due to malformed event ID/URL)

    :param session: ClientSession to connect through
    :param eid: Wanted event's event ID, available in the address bar
    """
    try:
        await getrequest(session, eid)
    except ContentTypeError as err:
        print("ContentTypeError! Malformed URL?")
        raise err


def get_timeout():
    """
    Asks the user for how long the GET request loop should run if no tickets are found
    before terminating the program

    :return: The user-input timeout, in seconds. Raises ValueError if the input format can not be parsed
    """
    timeout_raw = input("Page refresh timeout (MM:SS): ")
    try:
        mm, ss = timeout_raw.split(":")
        mm = int(mm)
        ss = int(ss)
    except ValueError as err:
        print("Improper time format! Use MM:SS!")
        raise err
    return mm * 60 + ss


async def main():
    # region User authentication tag reading
    try:
        user = read_user_file()
    except FileNotFoundError:
        input("--- Press enter to close ---")
        return
    print("File user.txt read succesfully")
    # endregion

    conn = TCPConnector(limit=10)  # Higher limits may flag your connection as an attack
    session = ClientSession(connector=conn)

    # region User authentication tag validation
    if await validate_user(session, user) != 200:
        await session.close()
        print("User authentication failed! Check that user.txt contains the proper authentication string (Bearer ...)")
        input("--- Press enter to close ---")
        return
    print("User authenticated succesfully")
    # endregion

    eid = input("Event ID: ")

    # region Event ID validation
    try:
        await validate_eid(session, eid)
    except ContentTypeError:
        await session.close()
        input("--- Press enter to close ---")
        return
    print("Event ID validated succesfully")
    # endregion

    # region Getting GET request timeout
    timeout = None
    while timeout is None:
        try:
            timeout = get_timeout()
        except ValueError:
            pass
    print(f"Timeout set to {timeout} seconds succesfully")
    # endregion

    input("~~~ PRESS ENTER TO START ~~~")

    start = time.time()  # To track timeout

    # region GET requests
    tickets_raw = asyncio.ensure_future(loop_getrequest(session, eid, timeout, start))
    await tickets_raw

    tickets = tickets_raw.result()
    # endregion

    # region POST requests
    posts = []
    for tic in tickets:
        iid = tic["inventoryId"]
        max_qty = tic["productVariantMaximumReservableQuantity"]
        post = asyncio.ensure_future(loop_postrequest(session, iid, max_qty, user))
        posts.append(post)
    await asyncio.gather(*posts, return_exceptions=True)
    # endregion

    await session.close()

    input("~~~ FINISHED, PRESS ENTER TO CLOSE ~~~")


if __name__ == "__main__":
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    asyncio.run(main())
