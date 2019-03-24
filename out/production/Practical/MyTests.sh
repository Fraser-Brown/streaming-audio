#!/bin/bash
echo "starting reorder"
/usr/sbin/tc qdisc add dev enp3s0 root netem delay 10ms reorder 25% 50%
sh server.sh
echo "cleaning up"
/usr/sbin/tc qdisc del dev enp3s0 root