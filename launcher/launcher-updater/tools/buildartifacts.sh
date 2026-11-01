#!/bin/bash

# Setup
chmod +x "$0"
artifacttoolbasedir="$(dirname "$0")"
artifacttoolbasedir="$(readlink -f "$artifacttoolbasedir")"

# Set up environment
source "$artifacttoolbasedir/src/env.sh"

# Source main source
sourceAll "$artifacttoolbasedir/src"
sourceAll "$artifacttoolbasedir/../menutool/src"

# Finish init
EventBus.fireEvent Core.finishSourceInit

# Run
artifacttool_main "$@"
