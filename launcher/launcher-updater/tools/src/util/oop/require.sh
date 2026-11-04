#!/bin/bash

# Requirement
if [ "${#OOPBASE_sourcelist_base[@]}" == 0 ]; then
    declare -g OOPBASE_sourcelist_base=()
fi
function arrayContains() {
    local key="$1"
    local array="$2"
    eval 'local arrIn=("${'"$array"'[@]}")'

    # Check
    for entry in "${arrIn[@]}"; do
        if [ "$entry" == "$key" ]; then
            return 0
        fi
    done
    return 1
}
function printStackTrace() {
    local i=0
    if [ "$1" != "" ]; then
        i=$1
    fi
    while : ; do
        local callStackEntry="$(caller "$i")"
        if [ "$callStackEntry" == "" ]; then
            break
        fi

        # Create stack entry
        local fileLine="$(echo "$callStackEntry" | sed "s/ .*//g")"
        local functionNameR="$(echo "$callStackEntry" | sed "s/[0-9]*//g" | sed "s/ //" | sed "s/ .*//g")"
        local functionName="$functionNameR()"
        local fileName="$(echo "$callStackEntry" | sed "s/$fileLine $functionNameR //g")"
        if [ "$functionName" == "main()" ] && [ "$(readlink -f "$fileName")" == "$(readlink -f "$artifacttoolbasedir/polytool")" ]; then
            functionName="<entry>"
        fi
        if ([ "$functionName" == "crash()" ] || [ "$functionName" == "runFunctionSafe()" ] || [ "$functionName" == "printStackTrace()" ]) && [ "$(readlink -f "$fileName")" == "$(readlink -f "$artifacttoolbasedir/src/util/stacktrace.sh")" ]; then
            i=$((i+1))
            continue
        fi

        # Echo
        1>&2 echo "  at $functionName in $fileName:$fileLine"
        i=$((i+1))
    done
}

# Set up require path
if [ "$REQUIREPATH" == "" ]; then
    export REQUIREPATH="$SOURCEBASEDIR"
fi
if [[ ":$REQUIREPATH:" != ":$SOURCEBASEDIR:" ]]; then
    export REQUIREPATH="$REQUIREPATH:$SOURCEBASEDIR"
fi

# Require command (imports)
function require() {
    local path="$1"   

    # Import

    # Get path
    if [[ "$path" != *"/"* ]]; then
        path="${path//.//}"
    fi

    # Get caller
    local callStackEntry="$(caller 0)"
    local fileLine="$(echo "$callStackEntry" | sed "s/ .*//g")"
    local functionNameR="$(echo "$callStackEntry" | sed "s/[0-9]*//g" | sed "s/ //" | sed "s/ .*//g")"
    local currentfile="$(echo "$callStackEntry" | sed "s/$fileLine $functionNameR //g")"
    local localpath="$(dirname "$currentfile")"

    # Locate
    local fullfile=""
    local REQUIREPATH="$REQUIREPATH:$localpath"
    IFS=':' read -ra PTH <<< "$REQUIREPATH"
    for dir in "${PTH[@]}" ; do
        # Check if file exists
        if [ -f "$dir/$path" ]; then
            fullfile="$(readlink -f "$dir/$path")"
            break
        fi
        if [ -f "$dir/$path.sh" ]; then
            fullfile="$(readlink -f "$dir/$path.sh")"
            break
        fi
        if [ -f "$dir/$path.bash" ]; then
            fullfile="$(readlink -f "$dir/$path.bash")"
            break
        fi
    done

    # Import
    if [ "$fullfile" != "" ]; then
        # Import file if needed
        if ! arrayContains "$fullfile" OOPBASE_sourcelist_base; then
            OOPBASE_sourcelist_base+=("$fullfile")
            source "$(readlink -f "$fullfile")"
        fi
    else
        1>&2 echo Error: unable to find import $path for $currentfile
        printStackTrace 1
        exit 1
    fi
}
