#!/bin/bash

cd /root/qa-jenkins-baremetal
echo "Executing rebuild"

sshpass -p cobbler ansible-playbook -i inventory provision.yml --forks 22 --ask-pass
echo "Finished provisioning, sleeping for 5 minutes"
sleep 300;

sshpass -p cobbler ansible-playbook -i inventory configure.yml --forks 22 --ask-pass 
echo "Finished initial configuration, sleeping for 5 minutes"
sleep 300;

sshpass -p cobbler ansible-playbook -i inventory stage.yml -e "openstack_release='${1}'" --forks 22 --ask-pass 
echo "Finished initial staging, sleeping for 5 minutes"
sleep 300;

[ -f ~/.ssh/known_hosts ] && rm -f ~/.ssh/known_hosts
cd /opt/openstack-ansible/playbooks

echo "Attempting to SSH into hosts before running OSA";

for s in $(grep 'ansible_ssh_host' /etc/openstack_deploy/openstack_inventory.json|perl -pe 's/.*: \"(.*)\",.*/$1/g'|sort|uniq|egrep '172.24.8'); do 
 sshpass -p cobbler ssh -o strictHostKeyChecking=False -o ConnectTimeout=10 ${s} "hostname" >/dev/null 2>&1;
done;

sleep 10

echo "Installing OpenStack via openstack-ansible";
echo "Running openstack-ansible setup-hosts.yml"

while true;
do
        openstack-ansible -i inventory setup-hosts.yml -f 21 -vvv
        RETVAL=$?
        if [ ${RETVAL} -ne 0 ];
        then
                echo "Setup hosts has failed.";
                exit 1;
        else
                echo "Running openstack-ansible setup-infrastructure.yml";
                break;
        fi;
done

sleep 300;

while true;
do
        openstack-ansible -i inventory setup-infrastructure.yml -f 21 -vvv
        RETVAL=$?
        if [ ${RETVAL} -ne 0 ];
        then
                echo "Setup infrastructure has failed.";
                exit 1;
        else
                echo "Running openstack-ansible setup-openstack.yml";
                break;
        fi;
done;

sleep 300;

while true;
do
        openstack-ansible -i inventory setup-openstack.yml -f 21 -vvv
        RETVAL=$?
        if [ ${RETVAL} -ne 0 ];
        then
                echo "Failure in setting up OpenStack.";
                exit 1;
        else
                echo "OpenStack successfully installed using openstack-ansible"
                exit 0;
        fi;
done;
