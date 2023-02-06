import asyncio
from aiohttp import ClientSession
from aiohttp import TCPConnector
from aiohttp import ContentTypeError
import datetime as dt
import time

"""
Kide.app Async HTTP Event Bot (KAHEB)
@author: Vertti Nuotio
@version: 1.4.4
"""

AUTH_URL = "https://api.kide.app/api/authentication/user"
GET_URL = "https://api.kide.app/api/products/"
POST_URL = "https://api.kide.app/api/reservations"
REQUEST_TIMEOUT = 30  # Timeout parameter for all aiohttp requests, seconds
GET_REQUEST_DELAY = 0.05  # How often a new GET request for ticket data should be sent, seconds.
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
        print("FileNotFoundError: user.txt not found!")
        raise err


async def validate_user(session, user):
    """
    Validates user authentication string against Kide.app API

    :param session: ClientSession to connect through
    :param user: Kide.app user authentication string ("Bearer ...")
    :return: User's full name if found, '???' if not.
    Raises a ValueError if the auth string can't be validated
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
    async with session.get(AUTH_URL, headers=headers, timeout=REQUEST_TIMEOUT) as res:
        if res.status != 200:
            print("User authentication failed! Check that user.txt contains the proper authentication string (Bearer ...)")
            raise ValueError
        try:
            json = await res.json()
            return json["model"]["fullName"]
        except KeyError:
            return "???"


async def validate_eid(session, eid):
    """
    Validates the event ID by pinging the Kide.app API and returning the status code

    :param session: ClientSession to connect through
    :param eid: Wanted event's ID, available in the address bar
    :return: JSON data (dict) of the event, mainly including its name and the time sales begin at-
    Raises a ValueError if event ID can't be validated, and KeyError if the JSON data is malformed
    """
    url = f"{GET_URL}/{eid}"
    async with session.get(url, timeout=REQUEST_TIMEOUT) as res:
        if res.status != 200:
            raise ValueError
        try:
            json = await res.json()
            return json["model"]["product"]
        except KeyError as err:
            raise err


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


async def loop_getrequest(session, eid, start):
    """
    Makes asynchronous GET requests to Kide.app API until a request returns with ticket data

    :param session: ClientSession to connect through
    :param eid: Wanted event's ID, available in the address bar
    :param start: the initialization moment as time.time() of this method
    :return: JSON data (list) of the page, which includes the tickets and their data if found,
    otherwise an empty list
    """
    tickets = []
    requests = []
    flag = asyncio.Event()
    while not flag.is_set():
        time_diff = time.time() - start
        if time_diff > REQUEST_TIMEOUT:
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


async def reserve_all_tickets(session, tickets, user, tag=None):
    """
    Reserves all given tickets asynchronously using Kide.app's API

    :param session: ClientSession to connect through
    :param tickets: JSON data for all ticket variants, includes their inventory ID's
    and maximumn reservable quantities
    :param user: Kide.app user authentication string ("Bearer ...")
    :param tag: Tag that could be found in the preferred ticket's name. The program
    will prioritize this ticket if found. If none is given, reserves all tickets
    """
    posts = []
    for tic in tickets:
        name_lower = tic["name"].lower()
        if (tag is None) or (tag in name_lower):
            iid = tic["inventoryId"]
            max_qty = tic["productVariantMaximumReservableQuantity"]
            post = asyncio.ensure_future(postrequest(session, iid, max_qty, user))
            posts.append(post)
    await asyncio.gather(*posts, return_exceptions=True)

    if tag is not None:
        await reserve_all_tickets(session, tickets, user, None)


# endregion


async def get_event_info(session):
    """
    Asks the user for event ID and gets the general info concerning the event (name, sales start time).
    Loops until a proper event ID is given and data is found.

    :param session: ClientSession to connect through
    :return: JSON data (dict) for the event. Raises KeyError if the JSON data is malformed and can't be accessed
    using preset keys
    """
    event_info = None
    while event_info is None:
        eid = input("Event ID: ").strip()
        try:
            event_info = await validate_eid(session, eid)
        except ValueError:
            print("Event not found! Malformed ID?\n")
            continue
        except KeyError as err:
            print("Event data malformed, could not access product name and date. Contact the author!")
            raise err

    event_name = event_info["name"]
    sales_start_iso = event_info["dateSalesFrom"]
    sales_start = dt.datetime.fromisoformat(sales_start_iso)
    sales_start_str = sales_start.strftime("%d %b @ %H:%M:%S")

    print("Event ID validated succesfully:")
    print(f"    Found event '{event_name}'")
    print(f"    Sales begin on {sales_start_str}")
    return event_info


def get_tag():
    tag = input("Preferred ticket search tag (enter to skip): ") \
        .strip() \
        .lower()
    if tag == "":
        print("Tag not set, reserving all possible tickets")
        return None
    else:
        print(f"Ticket preference tag set to '{tag}'")
        return tag


async def main():
    # region User authentication tag reading
    try:
        user = read_user_file()
    except FileNotFoundError:
        input("\n--- Press enter to close ---\n")
        return
    print("File user.txt read succesfully")
    # endregion

    conn = TCPConnector(limit=10)  # Higher limits may flag your connection as an attack
    session = ClientSession(connector=conn)

    # region User authentication tag validation
    try:
        user_name = await validate_user(session, user)
    except ValueError:
        await session.close()
        input("\n--- Press enter to close ---\n")
        return
    print("User authenticated succesfully:")
    print(f"   Found user '{user_name}'")
    # endregion

    # region Event ID validation and information assigning
    try:
        event_info = await get_event_info(session)
    except KeyError:
        await session.close()
        input("\n--- Press enter to close ---\n")
        return

    eid = event_info["id"]
    # endregion

    tag = get_tag()  # Ticket preference tag

    input("\n~~~ Setup ready! Press enter to confirm and start! ~~~\n")
    print("Starting refresh process...")
    start = time.time()  # To track timeout

    tickets = await loop_getrequest(session, eid, start)  # GET ticket page data
    await reserve_all_tickets(session, tickets, user, tag)  # Reserve all found tickets via POST requests
    await session.close()

    end = time.time()
    print(f"\nFinished! Time: {end-start}s.")

    input("~~~ Press enter to close ~~~\n")


if __name__ == "__main__":
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    asyncio.run(main())
