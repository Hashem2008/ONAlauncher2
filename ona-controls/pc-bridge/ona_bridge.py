#!/usr/bin/env python3
"""
ONA overlay bridge - receives the floating controls of ONA Launcher 2 over TCP and turns them into
real keyboard / mouse / gamepad input on a PC (Windows, Linux X11, macOS; also works for Wine games).

    pip install pynput            # keyboard + mouse
    pip install vgamepad          # optional, virtual Xbox pad (Windows, needs ViGEmBus)
    python ona_bridge.py --port 7788

In ONA: Floating controls -> "PC / Wine bridge (TCP)" -> PC address = this PC's LAN IP, same port.
Protocol (one line per event): see TcpBridgeBackend.kt.
"""
import argparse
import socket
import threading

from pynput.keyboard import Controller as Kb, Key
from pynput.mouse import Controller as Ms, Button

try:
    import vgamepad as vg
except Exception:  # optional
    vg = None

kb, ms = Kb(), Ms()

SPECIAL = {
    "ESC": Key.esc, "ENTER": Key.enter, "SPACE": Key.space, "TAB": Key.tab, "BKSP": Key.backspace,
    "DEL": Key.delete, "INSERT": Key.insert, "HOME": Key.home, "END": Key.end,
    "PAGE_UP": Key.page_up, "PAGE_DOWN": Key.page_down,
    "UP": Key.up, "DOWN": Key.down, "LEFT": Key.left, "RIGHT": Key.right,
    "L_SHIFT": Key.shift_l, "L_CTRL": Key.ctrl_l, "L_ALT": Key.alt_l,
    "R_SHIFT": Key.shift_r, "R_CTRL": Key.ctrl_r, "R_ALT": Key.alt_r, "CAPS": Key.caps_lock,
    "TILDE": "`", "MINUS": "-", "EQUALS": "=", "COMMA": ",", "PERIOD": ".", "SLASH": "/",
}
SPECIAL.update({f"F{i}": getattr(Key, f"f{i}") for i in range(1, 13)})
SPECIAL.update({f"K{i}": str(i) for i in range(10)})
BUTTONS = {"MLB": Button.left, "MRB": Button.right, "MMB": Button.middle}
SCREEN = None  # (width, height) of the PC screen, for absolute cursor moves ("P")


def screen_size():
    global SCREEN
    if SCREEN is None:
        try:
            import tkinter
            t = tkinter.Tk(); t.withdraw()
            SCREEN = (t.winfo_screenwidth(), t.winfo_screenheight()); t.destroy()
        except Exception:
            SCREEN = (1920, 1080)
    return SCREEN


def key_of(name):
    if name in SPECIAL:
        return SPECIAL[name]
    if len(name) == 1:
        return name.lower()
    return None


class Pad:
    def __init__(self):
        self.pad = vg.VX360Gamepad() if vg else None
        self.axes = {"LX": 0.0, "LY": 0.0, "RX": 0.0, "RY": 0.0}
        if self.pad:
            b = vg.XUSB_BUTTON
            self.map = {
                "GP_A": b.XUSB_GAMEPAD_A, "GP_B": b.XUSB_GAMEPAD_B, "GP_X": b.XUSB_GAMEPAD_X, "GP_Y": b.XUSB_GAMEPAD_Y,
                "GP_LB": b.XUSB_GAMEPAD_LEFT_SHOULDER, "GP_RB": b.XUSB_GAMEPAD_RIGHT_SHOULDER,
                "GP_SELECT": b.XUSB_GAMEPAD_BACK, "GP_START": b.XUSB_GAMEPAD_START,
                "GP_L3": b.XUSB_GAMEPAD_LEFT_THUMB, "GP_R3": b.XUSB_GAMEPAD_RIGHT_THUMB,
                "GP_DPAD_UP": b.XUSB_GAMEPAD_DPAD_UP, "GP_DPAD_DOWN": b.XUSB_GAMEPAD_DPAD_DOWN,
                "GP_DPAD_LEFT": b.XUSB_GAMEPAD_DPAD_LEFT, "GP_DPAD_RIGHT": b.XUSB_GAMEPAD_DPAD_RIGHT,
            }

    def button(self, name, down):
        if not self.pad:
            return
        if name in ("GP_LT", "GP_RT"):
            (self.pad.left_trigger_float if name == "GP_LT" else self.pad.right_trigger_float)(1.0 if down else 0.0)
        elif name in self.map:
            (self.pad.press_button if down else self.pad.release_button)(self.map[name])
        self.pad.update()

    def axis(self, name, v):
        if not self.pad:
            return
        if name in ("LT", "RT"):
            (self.pad.left_trigger_float if name == "LT" else self.pad.right_trigger_float)(max(0.0, v))
        else:
            self.axes[name] = v
            # Android y grows downwards, XInput y grows upwards
            self.pad.left_joystick_float(self.axes["LX"], -self.axes["LY"])
            self.pad.right_joystick_float(self.axes["RX"], -self.axes["RY"])
        self.pad.update()


def handle(conn, addr):
    print("connected", addr)
    pad = Pad()
    held = set()
    buf = b""
    try:
        while True:
            data = conn.recv(4096)
            if not data:
                break
            buf += data
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                p = line.decode("utf-8", "replace").split()
                if not p:
                    continue
                try:
                    if p[0] == "K":
                        k = key_of(p[1])
                        if k is not None:
                            if p[2] == "1":
                                kb.press(k); held.add(k)
                            else:
                                kb.release(k); held.discard(k)
                    elif p[0] == "M" and p[1] in BUTTONS:
                        (ms.press if p[2] == "1" else ms.release)(BUTTONS[p[1]])
                    elif p[0] == "W":
                        ms.scroll(0, int(p[1]))
                    elif p[0] == "R":
                        ms.move(int(p[1]), int(p[2]))
                    elif p[0] == "P":
                        w, h = screen_size()
                        ms.position = (int(float(p[1]) * w), int(float(p[2]) * h))
                    elif p[0] == "G":
                        pad.button(p[1], p[2] == "1")
                    elif p[0] == "A":
                        pad.axis(p[1], float(p[2]))
                except Exception as e:
                    print("bad line", line, e)
    finally:
        for k in list(held):  # never leave a key stuck when the phone disconnects
            kb.release(k)
        conn.close()
        print("disconnected", addr)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=7788)
    ap.add_argument("--screen", default=None, help="PC screen size for absolute moves, e.g. 1920x1080")
    a = ap.parse_args()
    if a.screen:
        global SCREEN
        w, h = a.screen.lower().split("x")
        SCREEN = (int(w), int(h))
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind((a.host, a.port))
    s.listen(2)
    print(f"ONA bridge listening on {a.host}:{a.port}" + ("" if vg else " (no vgamepad: gamepad events ignored)"))
    while True:
        c, addr = s.accept()
        c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        threading.Thread(target=handle, args=(c, addr), daemon=True).start()


if __name__ == "__main__":
    main()
