#!/bin/bash

# Remove LVM Logical Volume
umount /var/lib/nova
for i in `lvdisplay|awk '/LV Path/ { print $3 }'|awk '/nova/ { print $1 }'`
do
  lvchange -an $i
  lvremove -f $i
done
rm -rf /var/lib/nova

# Remove fstab entry
sed -i '/nova00/d' /etc/fstab
