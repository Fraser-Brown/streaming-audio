#!/bin/bash
sh client.sh pc3-009-l 23456 test.wav
sox test.wav -n spectrogram -o 'test.png'
