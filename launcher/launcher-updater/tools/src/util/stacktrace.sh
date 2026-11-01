#!/bin/bash

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

function crash() {
    local message="$1"
    echo
    echo 
    1>&2 echo CRASH!
    1>&2 echo "$message"
    printStackTrace
    exit 1
}

function runFunctionSafe() {
    local args=("$@")

    local function="$1"
    local functionParams=()
    arrayCopyOfRange args functionParams 1 "${#args[@]}"

    if type "$function" &>/dev/null ; then
        "$function" "${functionParams[@]}"
        return $?
    else
        crash "Call error: function $function is not defined"
        return 1
    fi
}