import websocket
import _thread
import rel
from termcolor import colored
from functools import partial
import sys

def on_message(ws, message):
    if message != "heartbeat":
        print(colored(">> " + message, "cyan"))

def on_error(ws, error):
    print(colored(">> communication error: " + error, "red"))

def on_close(ws, close_status_code, close_msg):
    print(colored(">> Connection closed from server", "red"))

def on_open(ws):
    print(colored(">> Opened connection","green"))

async def write(ws, message):
    ws.send(message)

def stop(ws):
    print(colored("\n>> Shutting down...", "green"))
    ws.close()
    rel.abort()

def get_user_input(ws):
    while True:
        message = input("")
        ws.send(message)

if __name__ == "__main__":
    ws = websocket.WebSocketApp(sys.argv[1],
                                on_open=on_open,
                                on_message=on_message,
                                on_error=on_error,
                                on_close=on_close)

    ws.run_forever(dispatcher=rel, reconnect=5)
    _thread.start_new_thread(get_user_input, (ws,))
    rel.signal(2, partial(stop, ws=ws))
    rel.dispatch()
