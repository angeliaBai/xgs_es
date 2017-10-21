#!/bin/bash
set -e

./clean.sh

./gen_root_ca.sh 12345678 12345678

./gen_node_cert.sh 0 12345678 12345678&& ./gen_node_cert.sh 1 12345678 12345678 &&  ./gen_node_cert.sh 2 12345678 12345678

./gen_client_node_cert.sh test 12345678 12345678

./gen_client_node_cert.sh spock 12345678 12345678
