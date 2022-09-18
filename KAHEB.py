import asyncio
from aiohttp import ClientSession
from aiohttp import TCPConnector
from aiohttp import ContentTypeError
import time

"""
Kide.app Async HTTP Event Bot (KAHEB)
@author: Vertti Nuotio
@version: 1.3.1
"""

AUTH_URL = "https://api.kide.app/api/authentication/user"
GET_URL = "https://api.kide.app/api/products/"
POST_URL = "https://api.kide.app/api/reservations"
REQUEST_TIMEOUT = 30  # Timeout parameter for all aiohttp requests, seconds
GET_REQUEST_DELAY = 0.1  # How often a new GET request for ticket data should be sent, seconds.
# NOTE! A delay too small may cause you to be flagged as an attacker (and the server probably can't keep up)


# region User authentication tag and event ID validation


def read_user_file():
    """
    Reads the file "user.txt" containing the Kide.app authentication string.
    Should be located in the same folder as the executable

    :return: User authentication string if succesfully read, raises a FileNotFoundError otherwise
    """
    try:
        with open("user.txt", mode="r") as file:
            user = file.read().strip()
            return user
    except FileNotFoundError as err:
        raise err


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


async def validate_eid(session, eid):
    """
    Validates the event ID by pinging the Kide.app API and returning the status code

    :param session: ClientSession to connect through
    :param eid: Wanted event's ID, available in the address bar
    :return: Response status code
    """
    url = f"{GET_URL}/{eid}"
    res = await session.get(url, timeout=REQUEST_TIMEOUT)
    return res.status


# endregion

# region Getting ticket data


async def getrequest(session, eid, tickets, flag):
    """
    Makes a GET request to the product catalogue API of Kide.app and adds
    the JSON data of the wanted event if found into given list 'result'

    :param session: ClientSession to connect through
    :param eid: Wanted event's event ID, available in the address bar
    :param tickets: List to add the ticket variants' data to, if found
    :param flag: When the ticket inventory ID's are found, this flag is set to stop any further GET requests.
    :return: Nothing. Data is instead stored into the 'result' variable (list)
    """
    url = f"{GET_URL}/{eid}"
    async with session.get(url, timeout=REQUEST_TIMEOUT) as res:
        try:
            json = await res.json()
            variants = json["model"]["variants"]
            if len(variants) > 0:  # 'variants' should always contain a list, even when empty
                tickets.append(variants)
                flag.set()
        except (ContentTypeError, KeyError):
            pass  # Passing is okay because we don't want this data anyways
        finally:
            return


async def loop_getrequest(session, eid, timeout, start):
    """
    Makes asynchronous GET requests to Kide.app API until a request returns with ticket data

    :param session: ClientSession to connect through
    :param eid: Wanted event's ID, available in the address bar
    :param timeout: how many seconds to loop for before exiting
    :param start: the initialization moment as time.time() of this method
    :return: JSON data of the page, which includes the tickets and their data if found,
    otherwise an empty list
    """
    tickets = []
    requests = []
    flag = asyncio.Event()
    while not flag.is_set():
        time_diff = time.time() - start
        if time_diff > timeout:
            print("GET requests timed out, quitting...")
            tickets.append([])  # Empty list so tickets[0] is valid (empty ticket data gets skipped over later)
            break

        await asyncio.sleep(GET_REQUEST_DELAY)
        req = asyncio.ensure_future(getrequest(session, eid, tickets, flag))
        requests.append(req)

    for req in requests:
        req.cancel()
    return tickets[0]


# endregion

# region Reserving tickets


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


async def reserve_all_tickets(session, tickets, user):
    """
    Reserves all given tickets asynchronously using Kide.app's API

    :param session: ClientSession to connect through
    :param tickets: JSON data for all ticket variants, includes their inventory ID's
    and maximumn reservable quantities
    :param user: Kide.app user authentication string ("Bearer ...")
    :return:
    """
    posts = []
    for tic in tickets:
        iid = tic["inventoryId"]
        max_qty = tic["productVariantMaximumReservableQuantity"]
        post = asyncio.ensure_future(postrequest(session, iid, max_qty, user))
        posts.append(post)
    await asyncio.gather(*posts, return_exceptions=True)


# endregion


def get_timeout():
    """
    Asks the user for how long the GET request loop should run if no tickets are found
    before terminating the program. Loops until a proper value is given

    :return: The user-input timeout, in seconds. Raises ValueError if the input format can not be parsed
    """
    timeout = None
    while timeout is None:
        timeout_raw = input("Page refresh timeout (MM:SS): ")
        try:
            mm, ss = timeout_raw.split(":")
            mm = int(mm)
            ss = int(ss)

            timeout = (60 * mm) + ss
        except ValueError:
            print("Improper time format! Use MM:SS!\n")
            continue

    print(f"Timeout set to {timeout} seconds succesfully")
    return timeout


async def main():
    # region User authentication tag reading
    try:
        user = read_user_file()
    except FileNotFoundError:
        print("FileNotFoundError: user.txt not found!")
        input("\n--- Press enter to close ---\n")
        return
    print("File user.txt read succesfully")
    # endregion

    conn = TCPConnector(limit=10)  # Higher limits may flag your connection as an attack
    session = ClientSession(connector=conn)

    # region User authentication tag validation
    if await validate_user(session, user) != 200:
        await session.close()
        print("User authentication failed! Check that user.txt contains the proper authentication string (Bearer ...)")
        input("\n--- Press enter to close ---\n")
        return
    print("User authenticated succesfully")
    # endregion

    eid = input("Event ID: ").strip()

    # region Event ID validation
    if await validate_eid(session, eid) != 200:
        await session.close()
        print("Event not found! Malformed ID?")
        input("\n--- Press enter to close ---\n")
        return
    print("Event ID validated succesfully")
    # endregion

    timeout = get_timeout()  # Page refreshing GET request timeout, seconds

    input("\n~~~ PRESS ENTER TO START ~~~\n")

    start = time.time()  # To track timeout

    tickets = await loop_getrequest(session, eid, timeout, start)  # GET ticket page data
    await reserve_all_tickets(session, tickets, user)  # Reserve all found tickets via POST requests
    await session.close()

    end = time.time()
    print(f"Time: {end-start}")

    input("\n~~~ FINISHED, PRESS ENTER TO CLOSE ~~~\n")


if __name__ == "__main__":
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    asyncio.run(main())
