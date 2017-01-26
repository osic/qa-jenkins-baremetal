# qa-jenkins-baremetal

## Required packages
cd /root  
apt-get update -y  
apt-get install -y python-dev curl  
wget -k https://bootstrap.pypa.io/get-pip.py  
python get-pip.py  

#### openstack_release <= Mitaka
Due to this bug - _https://github.com/ansible/ansible-modules-extras/issues/2042_  
pip 'install' 'git+https://github.com/ansible/ansible@stable-1.9'

#### openstack_release >= Newton
pip install ansible

## Clone this repo to deployment host /root directory
cd /root  
git clone http://github.com/osic/qa-jenkins-baremetal

## Change into repo directory
cd /root/qa-jenkins-baremetal

## Provision servers (does not include deployment host)
ansible-playbook -f 22 -i inventory provision.yml

## Configure servers (packages/networking/etc)
ansible-playbook -f 22 -i inventory configure.yml --ask-pass

## Prepare the deployment host to install openstack-ansible  
If openstack_release is not set master branch is used  
ansible-playbook -i inventory stage.yml -e "openstack_release='stable/mitaka'"
