#!/bin/bash

# Remove LVM Logical Volume
umount /deleteme
for i in `lvdisplay|awk '/LV Path/ { print $3 }'|awk '/deleteme/ { print $1 }'`
do
  lvchange -an $i
  lvremove -f $i
done
rm -rf /deleteme

# Remove fstab entry
sed -i '/deleteme00/d' /etc/fstab
