# Keyboard input to the VM through QMP send-key (US layout).
#   python3 type.py <socket> --text '<ASCII text>'      types the text
#   python3 type.py <socket> --keys ret ctrl-a alt-f4   QEMU key names, "-" joins a combination
import json
import socket
import sys
import time

SHIFTED = {'|': 'backslash', ':': 'semicolon', '"': 'apostrophe', '_': 'minus', '>': 'dot', '<': 'comma',
           '?': 'slash', '%': '5', '*': '8', '(': '9', ')': '0', '&': '7', '!': '1', '@': '2', '$': '4',
           '{': 'bracket_left', '}': 'bracket_right', '+': 'equal', '^': '6', '#': '3', '~': 'grave_accent'}
PLAIN = {' ': 'spc', '\\': 'backslash', ';': 'semicolon', "'": 'apostrophe', '-': 'minus', '.': 'dot',
         ',': 'comma', '/': 'slash', '=': 'equal', '[': 'bracket_left', ']': 'bracket_right',
         '`': 'grave_accent', '\n': 'ret', '\t': 'tab'}

s = socket.socket(socket.AF_UNIX)
s.connect(sys.argv[1])
f = s.makefile('rw')
f.readline()

def send(command):
    f.write(json.dumps(command) + '\n')
    f.flush()
    while 'event' in json.loads(f.readline()):
        pass

def press(keys):
    send({'execute': 'send-key', 'arguments': {'keys': [{'type': 'qcode', 'data': k} for k in keys], 'hold-time': 30}})
    time.sleep(0.08)  # faster than this and Windows drops keys or keeps Shift down

send({'execute': 'qmp_capabilities'})
if sys.argv[2] == '--keys':
    for combo in sys.argv[3:]:
        press(combo.split('-') if combo != 'minus' else ['minus'])
else:
    for c in sys.argv[3]:
        if c in SHIFTED:
            press(['shift', SHIFTED[c]])
        elif c.isupper():
            press(['shift', c.lower()])
        else:
            press([PLAIN.get(c, c)])
