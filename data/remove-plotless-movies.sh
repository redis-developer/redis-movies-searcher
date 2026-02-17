#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

redis-cli --eval "$SCRIPT_DIR/remove-plotless-movies.lua"

