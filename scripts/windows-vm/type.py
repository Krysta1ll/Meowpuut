# Types ASCII text into the VM with QMP send-key (US layout), then Enter.
#   python3 type.py <socket> '<text>'
import json
import socket
import sys

SHIFTED = {'|': 'backslash', ':': 'semicolon', '"': 'apostrophe', '_': 'minus', '>': 'dot', '<': 'comma',
           '?': 'slash', '%': '5', '*': '8', '(': '9', ')': '0', '&': '7', '!': '1', '@': '2', '$': '4',
           '{': 'bracket_left', '}': 'bracket_right', '+': 'equal'}
PLAIN = {' ': 'spc', '\\': 'backslash', ';': 'semicolon', "'": 'apostrophe', '-': 'minus', '.': 'dot',
         ',': 'comma', '/': 'slash', '=': 'equal', '[': 'bracket_left', ']': 'bracket_right'}

s = socket.socket(socket.AF_UNIX)
s.connect(sys.argv[1])
f = s.makefile('rw')
f.readline()

def send(command):
    f.write(json.dumps(command) + '\n')
    f.flush()
    while 'event' in json.loads(f.readline()):
        pass

send({'execute': 'qmp_capabilities'})
for c in sys.argv[2]:
    if c in SHIFTED:
        keys = ['shift', SHIFTED[c]]
    elif c.isupper():
        keys = ['shift', c.lower()]
    else:
        keys = [PLAIN.get(c, c)]
    send({'execute': 'send-key', 'arguments': {'keys': [{'type': 'qcode', 'data': k} for k in keys]}})
send({'execute': 'send-key', 'arguments': {'keys': [{'type': 'qcode', 'data': 'ret'}]}})
