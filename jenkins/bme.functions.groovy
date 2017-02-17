#!/usr/bin/env groovy

def get_deploy_node_ip() {
    // Get the onmetal host IP address
    if (fileExists('hosts')) {
        String hosts = readFile("hosts")
        String ip_match = /ansible_ssh_host=(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})/
        String ip = (hosts=~ip_match)[0][1]
        return (ip)
    } else {

        return (null)
    }
}

def setup_ssh_pub_key() {
    String host_ip = get_deploy_node_ip()

    sh """
        scp -o StrictHostKeyChecking=no ~/.ssh/id_rsa.pub root@${host_ip}:/root/temp_ssh_key.pub
    """
    // send key to all OS nodes to allow proxy commands
    try {
        sh """
            ssh -o StrictHostKeyChecking=no root@${host_ip} '''
                PUB_KEY=\$(cat /root/temp_ssh_key.pub)
                cd /opt/openstack-ansible/playbooks
                # key all utility containers
                ansible utility_all -i inventory -m shell -a \"echo \${PUB_KEY} >> /root/.ssh/authorized_keys\"
                # key all infra hosts
                ansible os-infra_hosts -i inventory -m shell -a \"echo \${PUB_KEY} >> /root/.ssh/authorized_keys\"
            '''
        """
    } catch(err) {
        echo "Failure passing key, this may not be significant depending on host"
        echo err.message
    }
}

def get_controller_utility_container_ip(controller_name='controller01') {
    // Rather than use all containers, find just one to operate tests
    String host_ip = get_deploy_node_ip()
    ip_output = sh returnStdout: true, script: """
        ssh -o StrictHostKeyChecking=no root@${host_ip} '''
            set -x
            cd /etc/openstack_deploy
            CONTAINER=\$(cat openstack_inventory.json | jq \".utility.hosts\" | grep \"${controller_name}_utility\")
            CONTAINER=\$(echo \$CONTAINER | sed s/\\\"//g | sed s/\\ //g | sed s/,//g )
            IP=\$(cat openstack_inventory.json | jq "._meta.hostvars[\\\""\$CONTAINER"\\\"].ansible_ssh_host" -r)
            echo "IP=\${IP}"
        '''
    """
    // quote in a comment to fix editor syntax highlighting '
    echo ip_output
    String container_ip = ip_output.substring(ip_output.indexOf('=') +1).trim()
    return (container_ip)
}

def get_tempest_dir(controller_name='controller01') {
  String host_ip = get_deploy_node_ip()
  String container_ip = get_controller_utility_container_ip(controller_name)
  String tempest_dir = ""
  try {
      tempest_dir = sh returnStdout: true, script: """
          ssh -o StrictHostKeyChecking=no\
          -o ProxyCommand='ssh -W %h:%p root@${host_ip}' root@${container_ip} '''
              TEMPEST_DIR=\$(find / -maxdepth 4 -type d -name "tempest*untagged" | head -1)
              echo \$TEMPEST_DIR
          '''
      """
  } catch(err) {
      echo "Error in determining Tempest location"
      throw err
  }
  return (tempest_dir)
}

def configure_tempest(controller_name='controller01', tempest_dir=null){
    String host_ip = get_deploy_node_ip()
    String container_ip = get_controller_utility_container_ip(controller_name)

    sh """
        ssh -o StrictHostKeyChecking=no\
        -o ProxyCommand='ssh -W %h:%p root@${host_ip}' root@${container_ip} '''
            TEMPEST_DIR=${tempest_dir}
            cd \$TEMPEST_DIR
            # Make sure tempest is installed
            pip install .
            testr init || echo "already configured"
            mkdir subunit || echo "subunit directory exists"
        '''
    """

    results = sh returnStdout: true, script: """
        ssh -o StrictHostKeyChecking=no\
        -o ProxyCommand='ssh -W %h:%p root@${host_ip}' root@${container_ip} '''
            # Make sure etc/tempest.conf exists
            TEMPEST_DIR=${tempest_dir}
            cd \$TEMPEST_DIR
            if [[ -f etc/tempest.conf ]]; then
                mv etc/tempest.conf etc/tempest.conf.orig
                wget https://raw.githubusercontent.com/osic/qa-jenkins-onmetal/master/jenkins/tempest.conf -O etc/tempest.conf
                # tempest.conf exists, overwrite it with required vars
                keys="admin_password image_ref image_ref_alt uri uri_v3 public_network_id"
                for key in \$keys
                do
                    a="\${key} ="
                    # overwrite each key in tempest conf to be "key ="
                    sed -ir "s|\$a.*|\$a|g" etc/tempest.conf
                    # get each key from generated tempest conf
                    b=\$(cat etc/tempest.conf.orig | grep "\$a")
                    # overwrite each key from original to downloaded tempest conf
                    sed -ir "s|\$a|\$b|g" etc/tempest.conf
                done
            else
                # On testing, if tempest.conf not populated, this needs to be modified
                # to create resources and place in tempest.conf
                echo "No existing tempest.conf"
            fi
        '''
    """
    if (results == "No existing tempest.conf"){
        echo "No existing tempest.conf"
    }
}

def run_tempest_tests(controller_name='controller01', regex='smoke', results_file = null, elasticsearch_ip = null, tempest_dir=null){
    String host_ip = get_deploy_node_ip()
    String container_ip = get_controller_utility_container_ip(controller_name)

    def failures

    tempest_output = sh returnStdout: true, script: """
        ssh -o StrictHostKeyChecking=no\
        -o ProxyCommand='ssh -W %h:%p root@${host_ip}' root@${container_ip} '''
            TEMPEST_DIR=${tempest_dir}
            cd \$TEMPEST_DIR
            stream_id=\$(cat .testrepository/next-stream)
            ostestr --regex ${regex} || echo 'Some smoke tests failed.'
            mkdir -p /opt/tempest_untagged/subunit/smoke
            cp .testrepository/\$stream_id /opt/tempest_untagged/subunit/smoke/${results_file}
        '''
    """
    if (tempest_output.contains('- Failed:') == true) {
        failures = tempest_output.substring(tempest_output.indexOf('- Failed:') + 10)
        failures = failures.substring(0,failures.indexOf('\n')).toInteger()
        if (failures >= 0) {
            echo 'Parsing failed smoke'
            echo 'Failures'
            echo tempest_output
            try {
                aggregate_parse_failed_smoke(host_ip, results_file, elasticsearch_ip, controller_name, tempest_dir)
            } catch (err){
                echo "error parsing failed smoke"
                echo err.message
            }
                //if (elasticsearch_ip != null) {
                //
                //}
            //error "${failures} tests from the Tempest smoke tests failed, stopping the pipeline."
        } else {
            echo 'The Tempest smoke tests were successfull.'
        }
    } else {
        error 'There was an error running the smoke tests, stopping the pipeline.'
    }
}

def install_persistent_resources_tests(controller_name='controller01', tempest_dir=null) {
    String host_ip = get_deploy_node_ip()
    String container_ip = get_controller_utility_container_ip(controller_name)
    // Install Persistent Resources tests on the utility container on ${controller}
    echo 'Installing Persistent Resources Tempest Plugin on the onMetal host'
    sh """
        ssh -o StrictHostKeyChecking=no\
        -o ProxyCommand='ssh -W %h:%p root@${host_ip}' root@${container_ip} '''
            TEMPEST_DIR=${tempest_dir}
            #rm -rf \$TEMPEST_DIR/persistent-resources-tests
            git clone https://github.com/osic/persistent-resources-tests.git \$TEMPEST_DIR/persistent-resources-tests || echo "dir exists"
            pip install --upgrade \$TEMPEST_DIR/persistent-resources-tests/
        '''
    """
}

def install_persistent_resources_tests_parse(controller_name='controller01', tempest_dir=null) {
    String host_ip = get_deploy_node_ip()
    String container_ip = get_controller_utility_container_ip(controller_name)
    // Install Persistent Resources tests parse on the utility container on ${controller}
    echo 'Installing Persistent Resources Tempest Plugin'
    sh """
        ssh -o StrictHostKeyChecking=no\
        -o ProxyCommand='ssh -W %h:%p root@${host_ip}' root@${container_ip} '''
            TEMPEST_DIR=${tempest_dir}
            rm -rf \$TEMPEST_DIR/persistent-resources-tests-parse
            git clone https://github.com/osic/persistent-resources-tests-parse.git \$TEMPEST_DIR/persistent-resources-tests-parse
            pip install --upgrade \$TEMPEST_DIR/persistent-resources-tests-parse/
        '''
    """
}

def run_persistent_resources_tests(controller_name='controller01', action='verify', results_file=null, tempest_dir=null){
    String host_ip = get_deploy_node_ip()
    String container_ip = get_controller_utility_container_ip(controller_name)

    if (results_file == null) {
        results_file = action
    }

    sh """
        ssh -o StrictHostKeyChecking=no\
        -o ProxyCommand='ssh -W %h:%p root@${host_ip}' root@${container_ip} '''
            TEMPEST_DIR=${tempest_dir}
            cd \$TEMPEST_DIR
            stream_id=\$(cat .testrepository/next-stream)
            ostestr --regex persistent-${action} || echo 'Some persistent resources tests failed.'
            mkdir -p /opt/tempest_untagged/subunit/persistent_resources/
            #cp .testrepository/\$stream_id \$TEMPEST_DIR/subunit/persistent_resources/${results_file}
            cp .testrepository/\$stream_id /opt/tempest_untagged/subunit/persistent_resources/${results_file}
        '''
    """
}

def parse_persistent_resources_tests(controller_name='controller01', tempest_dir=null){
    String host_ip = get_deploy_node_ip()
    String container_ip = get_controller_utility_container_ip(controller_name)

    sh """
        ssh -o StrictHostKeyChecking=no\
        -o ProxyCommand='ssh -W %h:%p root@${host_ip}' root@${container_ip} '''
            TEMPEST_DIR=${tempest_dir}
            cd \$TEMPEST_DIR/subunit/persistent_resources/
            mkdir /opt/tempest_untagged/output || "output directory exists"
            #resource-parse --u . > \$TEMPEST_DIR/output/persistent_resource.txt
            resource-parse --u . > /opt/tempest_untagged/output/persistent_resource.txt
            rm *.csv
        '''
    """
}

def transfer_tempest_configuration_to_controller(controller_name='controller01'){
  String container_ip = get_controller_utility_container_ip(controller_name)
  String host_ip = get_deploy_node_ip()

  try {
      sh """
          scp -o StrictHostKeyChecking=no\
          -o ProxyCommand='ssh -W %h:%p root@${host_ip}'\
          -r root@${container_ip}:/opt/tempest_untagged/etc .

          scp -o StrictHostKeyChecking=no\
          -o ProxyCommand='ssh -W %h:%p root@${host_ip}'\
          -r etc root@${controller_name}:/root/
      """
  } catch(err) {
      echo "Error moving tempest etc directory"
      echo err.message
  }
}

def install_during_upgrade_tests(controller_name='controller01', tempest_dir=null) {
    String host_ip = get_deploy_node_ip()
    //String container_ip = get_controller_utility_container_ip(controller_name)
    // Install during upgrade tests on the utility container on ${controller}
    echo 'Installing during upgrade test on ${controller}_utility container'
    sh """
        ssh -o StrictHostKeyChecking=no\
        -o ProxyCommand='ssh -W %h:%p root@${host_ip}' root@${controller_name} '''
            cd /root/
            mkdir -p /root/output || echo "output directory exists"
            rm -rf rolling-upgrades-during-test
            git clone https://github.com/osic/rolling-upgrades-during-test
            cd rolling-upgrades-during-test
            pip install -r requirements.txt
        '''
    """
}

def start_during_upgrade_test(controller_name='controller01', tempest_dir=null) {
    String host_ip = get_deploy_node_ip()
    //String container_ip = get_controller_utility_container_ip(controller_name)
    // Start during upgrade tests on the utility container on ${controller}
    sh """
        ssh -o StrictHostKeyChecking=no\
        -o ProxyCommand='ssh -W %h:%p root@${host_ip}' root@${controller_name} '''
            set -x
            cd /root/rolling-upgrades-during-test
            python call_test.py --daemon --output-file /root/output/during.uptime.out
        ''' &
    """
}

def stop_during_upgrade_test(controller_name='controller01', tempest_dir=null) {
    String host_ip = get_deploy_node_ip()
    // String container_ip = get_controller_utility_container_ip(controller_name)
    // Stop during upgrade tests on the utility container on ${controller}
    sh """
        ssh -o StrictHostKeyChecking=no\
        -o ProxyCommand='ssh -W %h:%p root@${host_ip}' root@${controller_name} '''
            touch /usr/during.uptime.stop
        '''
    """
}

def install_api_uptime_tests(controller_name='controller01', tempest_dir=null) {
    String host_ip = get_deploy_node_ip()
    //String container_ip = get_controller_utility_container_ip(controller_name)
    // install api uptime tests on utility container on ${controller}
    sh """
        ssh -o StrictHostKeyChecking=no\
        -o ProxyCommand='ssh -W %h:%p root@${host_ip}' root@${controller_name} '''
            mkdir -p /root/output || echo "output directory exists"
            rm -rf /root/api_uptime
            git clone https://github.com/osic/api_uptime.git /root/api_uptime
            cd /root/api_uptime
            pip install --upgrade -r requirements.txt
        '''
    """
}

def start_api_uptime_tests(controller_name='controller01', tempest_dir=null) {
    String host_ip = get_deploy_node_ip()
    String container_ip = get_controller_utility_container_ip(controller_name)
    // start api uptime tests on the utility container on ${controller}
    sh """
        ssh -o StrictHostKeyChecking=no\
        -o ProxyCommand='ssh -W %h:%p root@${host_ip}' root@${controller_name} '''
            set -x
            rm -f /usr/api.uptime.stop
            cd /root/api_uptime/api_uptime
            python call_test.py --verbose --daemon --services nova,swift\
             --output-file /root/output/api.uptime.out
        ''' &
    """
}

def stop_api_uptime_tests(controller_name='controller01', tempest_dir=null) {
    String host_ip = get_deploy_node_ip()
    // String container_ip = get_controller_utility_container_ip(controller_name)
    // Stop api uptime tests on the utility container on ${controller}
    sh """
        ssh -o StrictHostKeyChecking=no\
        -o ProxyCommand='ssh -W %h:%p root@${host_ip}' root@${controller_name} '''
            set -x
            ps aux|grep call_test > output/processes.txt
            touch /usr/api.uptime.stop

            # Wait up to 60 seconds for the results file gets created by the script
            x=0
            while [ \$x -lt 600 -a ! -e /root/output/api.uptime.out ]; do
                x=\$((x+1))
                sleep .1
            done
        '''
    """
}

def install_tempest_tests(controller_name='controller01') {
    String host_ip = get_deploy_node_ip()

    sh """
        ssh -o StrictHostKeyChecking=no root@${host_ip} '''
        cd /opt/openstack-ansible/playbooks
        openstack-ansible os-tempest-install.yml
        '''
    """
}

def aggregate_parse_failed_smoke(host_ip, results_file, elasticsearch_ip, controller_name='controller01', tempest_dir=null) {
    String container_ip = get_controller_utility_container_ip(controller_name)

    try {
        sh """
            TEMPEST_DIR=${tempest_dir}
            scp -o StrictHostKeyChecking=no\
            -o ProxyCommand='ssh -W %h:%p root@${host_ip}'\
            -r root@${container_ip}:\$TEMPEST_DIR/output .

            scp -o StrictHostKeyChecking=no\
            -r output ubuntu@${elasticsearch_ip}:/home/ubuntu/
        """
    } catch(err) {
        echo "Error moving output directory"
        echo err.message
    }

    try {
        sh """
            TEMPEST_DIR=${tempest_dir}
            scp -o StrictHostKeyChecking=no\
            -o ProxyCommand='ssh -W %h:%p root@${host_ip}'\
            -r root@${container_ip}:\$TEMPEST_DIR/subunit .

            scp -o StrictHostKeyChecking=no\
            -r subunit ubuntu@${elasticsearch_ip}:/home/ubuntu/
        """
    } catch(err) {
        echo "No subunit directory found"
        echo err.message
    }

    if (results_file == 'after_upgrade'){
        sh """
            ssh -o StrictHostKeyChecking=no ubuntu@${elasticsearch_ip} '''
                elastic-upgrade -u \$HOME/output/api.uptime.out\
                -d \$HOME/output/during.uptime.out -p \$HOME/output/persistent_resource.txt\
                -b \$HOME/subunit/smoke/before_upgrade -a \$HOME/subunit/smoke/after_upgrade

                elastic-upgrade -s \$HOME/output/nova_status.json,\
                \$HOME/output/swift_status.json,\$HOME/output/keystone_status.json
            '''
        """
    } else {
      sh """
          ssh -o StrictHostKeyChecking=no ubuntu@${elasticsearch_ip} '''
              elastic-upgrade -b \$HOME/subunit/smoke/before_upgrade
          '''
      """
    }
}

def cleanup_test_results() {
  // Clean up existing tests results on elasticsearch node
  try {
      sh """
          rm -rf /home/ubuntu/output || echo "no output directory exists"
          rm -rf /home/ubuntu/subunit || echo "no subunit directory exists"
          rm -rf /home/ubuntu/uptime_output || echo "no subunit directory exists"
      """
      echo "Previous test runs removed"
  } catch(err) {
     echo "Error removing previous test runs"
     echo err.message
  }
}

def connect_vpn(host=null, user=null, pass=null){
    // connects vpn on jenkins builder via f5fpc
    if (!host || !user || !pass){
        error 'Missing required parameter'
    }
    sh """
    set -x
    alias f5fpc="/usr/local/bin/f5fpc"
    function vpn_info() { f5fpc --info | grep -q "Connection Status"; echo \$?; }
    if [[ \$(vpn_info) -eq 0 ]]; then
        echo "VPN connection already established"
    else
      f5fpc --start --host https://${host}\
         --user ${user} --password ${pass} --nocheck &
      test_vpn=0
      while [[ \$(vpn_info) -ne 0 ]]; do
        # wait for vpn, up to 20 seconds
        if [[ \${test_vpn} -gt 20 ]]; then
          echo "Could not establish VPN"
          exit 2
        fi
        test_vpn=\$(expr \$test_vpn + 1)
        sleep 1
      done
      # adding a sleep to let the connection complete
      sleep 5
      echo "VPN established"
    fi
    """
}

def disconnect_vpn(){
    sh """
  set -x
  alias f5fpc="/usr/local/bin/f5fpc"
  function vpn_info() { f5fpc --info | grep -q "Connection Status"; echo \$?; }
  if [[ \$(vpn_info) -eq 1 ]]; then
      echo "VPN not connected"
  else
    f5fpc --stop &
    test_vpn=0
    while [[ \$(vpn_info) -ne 1 ]]; do
      # wait for vpn, up to 20 seconds
      if [[ \${test_vpn} -gt 20 ]]; then
        echo "Error disconnecting VPN"
        exit 2
      fi
      test_vpn=\$(expr \$test_vpn + 1)
      sleep 1
    done
    echo "VPN disconnected"
  fi
  """
}

def rebuild_environment() {
    String host_ip = get_deploy_node_ip()

    sh """
        ssh -o StrictHostKeyChecking=no root@${host_ip} '''
            cd /root
            bash rebuild_environment.sh
        '''
    """
}

def bash_upgrade_openstack(release='master', retries=2, fake_results=false) {
    // ***Requires Params ***
    // release (to upgrade to) - master or stable/ocata
    // retries - number of times to rerun
    // fake_results calls different method to return expected fails for testing

    String host_ip = get_deploy_node_ip()
    String upgrade_output = ""

    //call upgrade, fake_results allows testing while environment not usable
    echo "Running upgrade"
    if (fake_results) {
        upgrade_output = fake_run_upgrade_return_results(release, host_ip)
    } else {
        upgrade_output = run_upgrade_return_results(release, host_ip)
    }
    //take upgrade_output, find out if it's got a failure in it
    echo "Echoing Upgrade Results"
    echo "-----------------------"
    echo upgrade_output
    echo "-----------------------"

    String failure_output = parse_upgrade_results_for_failure(upgrade_output)

    if (failure_output.length() > 0) {
        // we have fails, rerun upgrade until it suceeds or to retry limit
        echo "Upgrade failed"
        for (int i = 0; i < retries; i++){
            echo "Rerunning upgrade, retry #" + (i + 1)
            if (fake_results) {
                upgrade_output = fake_run_upgrade_return_results(release, host_ip)
            } else {
                upgrade_output = run_upgrade_return_results(release, host_ip)
            }
            failure_output = parse_upgrade_results_for_failure(upgrade_output)
            if (failure_output.length() == 0){
                echo "Upgrade succeeded"
                break
            } else if (i == (retries -1)){
                echo "Upgrade failed, exceeded retries"
            }
        }
    }
    echo "Echoing Upgrade Results"
    echo "-----------------------"
    echo upgrade_output
    echo "-----------------------"
}

def fake_run_upgrade_return_results(release='master', host_ip="127.0.0.1"){
    //fakes it
    String upgrade_output = ""
    String failure_output = ""

    upgrade_output = sh returnStdout: true, script: """
        ssh -o StrictHostKeyChecking=no root@${host_ip} '''
        echo "******************** failure ********************"
        echo "The upgrade script has encountered a failure."
        echo 'Failed on task \"rabbitmq-install.yml -e 'rabbitmq_upgrade=true'\"'
        echo "Re-run the run-upgrade.sh script, or"
        echo "execute the remaining tasks manually:"
        echo "openstack-ansible rabbitmq-install.yml -e 'rabbitmq_upgrade=true'"
        echo "openstack-ansible etcd-install.yml"
        echo "openstack-ansible utility-install.yml"
        echo "openstack-ansible rsyslog-install.yml"
        echo "openstack-ansible /opt/openstack-ansible/scripts/upgrade-utilities/playbooks/memcached-flush.yml"
        echo "openstack-ansible setup-openstack.yml"
        echo "******************** failure ********************"
        bash -c "exit 2" || echo "Failed Upgrade"
        '''
    """
    return upgrade_output
}

def clear_ssh_host_key(controller_name="controller01") {
    sh """ ssh-keygen -f "/home/ubuntu/.ssh/known_hosts" -R $controller_name"""
}

def run_upgrade_return_results(release="master", host_ip="127.0.0.1"){
    String upgrade_output = ""
    String failure_output = ""

    upgrade_output = sh returnStdout: true, script: """
        ssh -o StrictHostKeyChecking=no root@${host_ip} '''
        set -x
        cd /opt/openstack-ansible
        git checkout ${release}
        git pull
        #LATEST_TAG=\$(git describe --abbrev=0 --tags)
        #git checkout \${LATEST_TAG}
        export TERM=xterm
        export I_REALLY_KNOW_WHAT_I_AM_DOING=true
        echo "YES" | bash scripts/run-upgrade.sh 2>&1 || echo "Failed Upgrade"
        '''
    """
    return upgrade_output
}

def parse_upgrade_results_for_failure(upgrade_output = null){
  // Looking for failed output such as:
  // ******************** failure ********************
  // The upgrade script has encountered a failure.
  // Failed on task "rabbitmq-install.yml -e 'rabbitmq_upgrade=true'"
  // Re-run the run-upgrade.sh script, or
  // execute the remaining tasks manually:
  // openstack-ansible rabbitmq-install.yml -e 'rabbitmq_upgrade=true'
  // openstack-ansible etcd-install.yml
  // openstack-ansible utility-install.yml
  // openstack-ansible rsyslog-install.yml
  // openstack-ansible /opt/openstack-ansible/scripts/upgrade-utilities/playbooks/memcached-flush.yml
  // openstack-ansible setup-openstack.yml
  // ******************** failure ********************
  // * Caveat, this only grabs the first failure block and returns it (assumes all controllers will
  // either fail the same way, or we're just going to act on any fail the same way)
  split_output = upgrade_output.split("\n")
  String failure_output = ""
  boolean failure_found = false
  boolean record = false
  for (int i = 0; i < split_output.size(); i++){
    if (split_output[i] == "******************** failure ********************"){
      if (record){
        // if we're already recording, then we've already found a failure line
        record = false
        failure_output = failure_output.trim()
        break
      } else {
        // we haven't started recording, so this is the first failure indicator
        // set flag to record, and that there is a failure
        record = true
        failure_found = true
      }
    } else if (record) {
      // we're recording, so record it
      failure_output = failure_output + split_output[i] + "\n"
    }
  }
  // return failure found, or an empty string
  if (failure_found){
    return (failure_output)
  } else {
    return ("")
  }

}

def aggregate_results(host_ip, elasticsearch_ip, tempest_dir=null, controller_name="controller01") {
    String container_ip = get_controller_utility_container_ip(controller_name)
    try {
       sh """
           set -x
           TEMPEST_DIR=${tempest_dir}
           scp -o StrictHostKeyChecking=no\
           -o ProxyCommand='ssh -W %h:%p root@${host_ip}'\
           -r root@${container_ip}:\$TEMPEST_DIR/output .

           scp -o StrictHostKeyChecking=no\
           -r output ubuntu@${elasticsearch_ip}:/home/ubuntu/
       """
   } catch(err) {
       echo "Error moving output directory"
       echo err.message
   }
   try {
      sh """
          scp -o StrictHostKeyChecking=no\
          -o ProxyCommand='ssh -W %h:%p root@${host_ip}'\
          -r root@${controller_name}:/root/output .

          scp -o StrictHostKeyChecking=no\
          output/* ubuntu@${elasticsearch_ip}:/home/ubuntu/output/
      """
  } catch(err) {
      echo "Error moving output directory"
      echo err.message
  }
   try {
       sh """
           set -x
           TEMPEST_DIR=${tempest_dir}
           scp -o StrictHostKeyChecking=no\
           -o ProxyCommand='ssh -W %h:%p root@${host_ip}'\
           -r root@${container_ip}:\$TEMPEST_DIR/subunit .

           scp -o StrictHostKeyChecking=no\
           -r subunit ubuntu@${elasticsearch_ip}:/home/ubuntu/
       """
   } catch(err) {
       echo "No subunit directory found"
       echo err.message
   }
}



def upgrade_openstack(release = 'master') {

    try {
        // Upgrade OSA to a specific release
        echo "Running the following playbook: upgrade_osa, to upgrade to the following release: ${release}"
        ansiblePlaybook extras: "-e openstack_release=${release}", inventory: 'hosts', playbook: 'upgrade_osa.yaml', sudoUser: null
    } catch (err) {
        echo "Retrying upgrade, failure on first attempt: " + err
        // Retry Upgrade OSA to a specific release
        ansiblePlaybook extras: "-e openstack_release=${release}", inventory: 'hosts', playbook: 'upgrade_osa.yaml', sudoUser: null
    }
}

// The external code must return it's contents as an object
return this
