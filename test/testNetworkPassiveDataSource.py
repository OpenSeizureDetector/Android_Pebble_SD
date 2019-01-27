#!/usr/bin/python

import requests

content = {"testData":"test data string"}

request = requests.post("http://192.168.0.161:8080/data", json=content)


