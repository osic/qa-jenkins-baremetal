#!/bin/bash
str=`for i in $(cobbler system list); do NETBOOT=$(cobbler system report --name $i | awk '/^Netboot/ {print $NF}'); if [[ ${NETBOOT} == True ]]; then echo -e "$i: netboot_enabled : ${NETBOOT}"; fi; done`
while  [ -n "$str" ]; do
 sleep 1;
 str=`for i in $(cobbler system list); do NETBOOT=$(cobbler system report --name $i | awk '/^Netboot/ {print $NF}'); if [[ ${NETBOOT} == True ]]; then echo -e "$i: netboot_enabled : ${NETBOOT}"; fi; done`;
done

# set -eu

# #Build array of system names
# count=0
# str=( )
# for i in `cobbler system list`
# do
#   str[$count]="$i"
#   (( count++ ))
# done

# #Pull one system from array and check if netboot-enabled
# #Once netboot is not enabled on system, move to next in array
# count=0
# while [[ ! -z "${str[@]:+${str[@]}}" ]]
# do
#   node=${str[$count]}
#   status=${node}
#   while [[ ! -z "${status// }"  ]]
#   do
#     status=$(cobbler system find --name $node --netboot-enabled=True)
#     sleep 1;
#   done
#   str=( "${str[@]/$node}" )
#   (( count++ ))
# done
