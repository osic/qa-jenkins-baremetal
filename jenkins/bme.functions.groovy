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

def run_tempest_tests(controller_name='controller01', regex='smoke', results_file = null, elasticsearch_ip = null){
    String host_ip = get_deploy_node_ip()

    def failures

    tempest_output = sh returnStdout: true, script: """
        ssh -o StrictHostKeyChecking=no\
        -o ProxyCommand='ssh -W %h:%p root@${host_ip}' root@${controller_name} '''
            cd /root/tempest
            stream_id=\$(cat .testrepository/next-stream)
            ostestr --regex ${regex} || echo 'Some smoke tests failed.'
            mkdir -p subunit/smoke || echo "smoke subunit directory exists"
            cp .testrepository/\$stream_id subunit/smoke/${results_file}
        '''
    """
    if (tempest_output.contains('- Failed:') == true) {
        failures = tempest_output.substring(tempest_output.indexOf('- Failed:') + 10)
        failures = failures.substring(0,failures.indexOf('\n')).toInteger()
        echo failures + ' failures in Tempest smoke tests'
    } else {
        echo "Tempest tests succeeded"
    }
    echo tempest_output
    try {
        sh """
            ssh -o StrictHostKeyChecking=no\
            -o ProxyCommand='ssh -W %h:%p root@${host_ip}' root@${controller_name} '''
                cd /root/tempest/
                testr last --subunit | subunit2junitxml > tempest_junit_results.xml
            '''
        """
        sh """
            scp -o StrictHostKeyChecking=no\
            -o ProxyCommand='ssh -W %h:%p root@${host_ip}'\
             root@${controller_name}:/root/tempest/tempest_junit_results.xml .
         """
         step([$class: 'XUnitBuilder',
                thresholds: [[$class: 'FailedThreshold', unstableThreshold: '3']],
                tools: [[$class: 'JUnitType', pattern: 'tempest_junit_results.xml']]])
      } catch(err) {
          echo "Error running junit tests"
          echo err.message
      }
}

def install_persistent_resources_tests(controller_name='controller01') {
    String host_ip = get_deploy_node_ip()

    // Install Persistent Resources tests on the utility container on ${controller}
    echo 'Installing Persistent Resources Tempest Plugin on ' + controller_name
    sh """
        ssh -o StrictHostKeyChecking=no\
        -o ProxyCommand='ssh -W %h:%p root@${host_ip}' root@${controller_name} '''
            cd /root/tempest
            if [[ -d persistent-resources-tests ]]; then
                cd persistent-resources-tests
                git pull
            else
                git clone https://github.com/osic/persistent-resources-tests.git
                cd persistent-resources-tests
            fi
            pip install --upgrade .
        '''
    """
}

def install_persistent_resources_tests_parse(controller_name='controller01') {
    String host_ip = get_deploy_node_ip()
    // Install Persistent Resources tests parse on the controller node ${controller_name}
    echo 'Installing Persistent Resources Tempest Plugin'
    sh """
        ssh -o StrictHostKeyChecking=no\
        -o ProxyCommand='ssh -W %h:%p root@${host_ip}' root@${controller_name} '''
            cd /root/tempest
            if [[ -d persistent-resources-tests-parse ]]; then
                cd persistent-resources-tests-parse
                git pull
            else
                git clone https://github.com/osic/persistent-resources-tests-parse.git
                cd persistent-resources-tests-parse
            fi
            pip install --upgrade .
        '''
    """
}

def run_persistent_resources_tests(controller_name='controller01', action='verify', results_file=null){
    String host_ip = get_deploy_node_ip()

    if (results_file == null) {
        results_file = action
    }

    sh """
        ssh -o StrictHostKeyChecking=no\
        -o ProxyCommand='ssh -W %h:%p root@${host_ip}' root@${controller_name} '''
            cd /root/tempest
            stream_id=\$(cat .testrepository/next-stream)
            ostestr --regex persistent-${action} || echo 'Some persistent resources tests failed.'
            mkdir -p subunit/persistent_resources/
            cp .testrepository/\$stream_id subunit/persistent_resources/${results_file}
        '''
    """
    try {
        sh """
            ssh -o StrictHostKeyChecking=no\
            -o ProxyCommand='ssh -W %h:%p root@${host_ip}' root@${controller_name} '''
                cd /root/tempest/
                testr last --subunit | subunit2junitxml > persistent_${action}_junit_results.xml
            '''
        """
        sh """
            scp -o StrictHostKeyChecking=no\
            -o ProxyCommand='ssh -W %h:%p root@${host_ip}'\
             root@${controller_name}:/root/tempest/persistent_${action}_junit_results.xml .
         """
         step([$class: 'XUnitBuilder',
                thresholds: [[$class: 'FailedThreshold', unstableThreshold: '3']],
                tools: [[$class: 'JUnitType', pattern: 'persistent_${action}_junit_results.xml']]])
    } catch(err) {
        echo "Error running junit on persistent-${action} tests"
        echo err.message
    }
}

def parse_persistent_resources_tests(controller_name='controller01'){
    String host_ip = get_deploy_node_ip()

    sh """
        ssh -o StrictHostKeyChecking=no\
        -o ProxyCommand='ssh -W %h:%p root@${host_ip}' root@${controller_name} '''
            cd /root/tempest/subunit/persistent_resources/
            mkdir /root/tempest/output || echo "output directory exists"
            resource-parse --u . > /root/tempest/output/persistent_resource.txt
            rm *.csv
        '''
    """
}

def install_rally(controller_name='controller01') {
    String host_ip = get_deploy_node_ip()

    echo 'Installing rally on ${controller_name}'
    sh """
        ssh -o StrictHostKeyChecking=no\
        -o ProxyCommand='ssh -W %h:%p root@${host_ip}' root@${controller_name} '''
            rm -rf rally.git/ || echo "rally directory does not exist"
            apt-get install -y libpq-dev libxml2-dev libxslt1-dev
            wget -q -O- https://raw.githubusercontent.com/openstack/rally/master/install_rally.sh | bash
            cd rally.git/
            git clone https://github.com/osic/rally-scenarios.git
            cd rally-scenarios/
            python extract_values.py
        '''
    """
}

def prime_rally_benchmarks(controller_name='controller01') {
    String host_ip = get_deploy_node_ip()

    echo 'Priming rally benchmarks on ${controller_name}'
    sh """
        ssh -o StrictHostKeyChecking=no\
        -o ProxyCommand='ssh -W %h:%p root@${host_ip}' root@${controller_name} '''
            cd /root/
            source openrc
            rally-manage db recreate
            rally deployment create --fromenv --name=existing
            rally deployment use --deployment existing
            cd rally.git/rally-scenarios/
            rally task start osic-keystone-prime-scenario.json --task-args-file args.yaml
            rally task start osic-nova-1-server-scenario.json --task-args-file args.yaml
        '''
    """
}

def run_rally_benchmarks(controller_name='controller01', results_file = 'results') {
    String host_ip = get_deploy_node_ip()

    echo 'Running rally benchmarks on ${controller_name}'
    sh """
        ssh -o StrictHostKeyChecking=no\
        -o ProxyCommand='ssh -W %h:%p root@${host_ip}' root@${controller_name} '''
            cd /root/
            source openrc
            rally deployment use --deployment existing
            cd rally.git/rally-scenarios/
            rally task start benchmark.json --task-args-file args.yaml
            rally task results > /root/output/${results_file}.json
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
            -r root@${container_ip}:/root/openrc .

            scp -o StrictHostKeyChecking=no\
            -o ProxyCommand='ssh -W %h:%p root@${host_ip}'\
            -r etc root@${controller_name}:/root/

            rm -rf etc

            scp -o StrictHostKeyChecking=no\
            -o ProxyCommand='ssh -W %h:%p root@${host_ip}'\
            -r openrc root@${controller_name}:/root/

            rm -f openrc
        """
    } catch(err) {
        echo "Error moving tempest etc directory"
        echo err.message
    }
}

def install_during_upgrade_tests(controller_name='controller01') {
    String host_ip = get_deploy_node_ip()
    // Install during upgrade tests on ${controller}
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

def start_during_upgrade_test(controller_name='controller01') {
    String host_ip = get_deploy_node_ip()
    // Start during upgrade tests ${controller}
    sh """
        ssh -o StrictHostKeyChecking=no\
        -o ProxyCommand='ssh -W %h:%p root@${host_ip}' root@${controller_name} '''
            set -x
            cd /root/rolling-upgrades-during-test
            python call_test.py --daemon -s swift,keystone --output-file /root/output/during.uptime.out > /root/output/during_log_1
        ''' &
    """
}

def start_nova_during_upgrade_test(controller_name='controller01') {
    String host_ip = get_deploy_node_ip()
    // Start during upgrade tests on the utility container on ${controller}
    sh """
        ssh -o StrictHostKeyChecking=no\
        -o ProxyCommand='ssh -W %h:%p root@${host_ip}' root@${controller_name} '''
            set -x
            cd /root/rolling-upgrades-during-test
            python call_test.py --daemon -m -s nova --output-file /root/output/during.novauptime.out > /root/output/during_log_2
        ''' &
    """
}

def stop_during_upgrade_test(controller_name='controller01') {
    String host_ip = get_deploy_node_ip()
    // Stop during upgrade tests on ${controller}
    sh """
        ssh -o StrictHostKeyChecking=no\
        -o ProxyCommand='ssh -W %h:%p root@${host_ip}' root@${controller_name} '''
            touch /usr/during.uptime.stop
            ps aux|grep call_test > /root/output/after_stopdur_processes.txt
        '''
    """
}

def install_api_uptime_tests(controller_name='controller01') {
    String host_ip = get_deploy_node_ip()
    // install api uptime tests on ${controller}
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

def start_api_uptime_tests(controller_name='controller01') {
    String host_ip = get_deploy_node_ip()
    // start api uptime tests on ${controller}
    sh """
        ssh -o StrictHostKeyChecking=no\
        -o ProxyCommand='ssh -W %h:%p root@${host_ip}' root@${controller_name} '''
            set -x
            rm -f /usr/api.uptime.stop
            cd /root/api_uptime/api_uptime
            python call_test.py --daemon --output-file /root/output/api.uptime.out
        ''' &
    """
}

def stop_api_uptime_tests(controller_name='controller01') {
    String host_ip = get_deploy_node_ip()
    // Stop api uptime tests on ${controller}
    sh """
        ssh -o StrictHostKeyChecking=no\
        -o ProxyCommand='ssh -W %h:%p root@${host_ip}' root@${controller_name} '''
            set -x
            ps aux|grep call_test > /root/output/processes.txt
            touch /usr/api.uptime.stop
            ps aux|grep call_test > /root/output/after_stopapi_processes.txt

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
    String container_ip = get_controller_utility_container_ip(controller_name)

    // install tempest via playbook to generate configuration
    sh """
        ssh -o StrictHostKeyChecking=no root@${host_ip} '''
        cd /opt/openstack-ansible/playbooks
        openstack-ansible os-tempest-install.yml
        '''
    """

    // install tempest master on controller node
    sh """
        ssh -o StrictHostKeyChecking=no\
        -o ProxyCommand='ssh -W %h:%p root@${host_ip}' root@${controller_name} '''
            # install tempest master
            cd /root/
            if [[ -d tempest ]]; then
                cd tempest/
                git pull
            else
                git clone https://github.com/openstack/tempest
                cd tempest/
            fi
            pip install --upgrade .
            # for junit results
            pip install --upgrade junitxml
            testr init || echo "already configured"
            mkdir subunit || echo "subunit directory exists"
        '''
    """

    // get generated configuration
    try {
       sh """
          scp -o StrictHostKeyChecking=no\
          -o ProxyCommand='ssh -W %h:%p root@${host_ip}'\
           root@${container_ip}:/openstack/venvs/tempest-untagged/etc/tempest.conf .

          scp -o StrictHostKeyChecking=no\
          -o ProxyCommand='ssh -W %h:%p root@${host_ip}'\
           tempest.conf root@${controller_name}:/root/tempest/etc/

          rm -f tempest.conf
       """
    } catch(err) {
        echo "Error moving tempest configuration"
        echo err.message
    }

    // update configuration using template from github and generated results from playbook
    sh """
        ssh -o StrictHostKeyChecking=no\
        -o ProxyCommand='ssh -W %h:%p root@${host_ip}' root@${controller_name} '''
            set -x
            cd /root/tempest
            if [[ -f etc/tempest.conf ]]; then
                mv etc/tempest.conf etc/tempest.conf.orig
                wget https://raw.githubusercontent.com/osic/qe-jenkins-baremetal/master/jenkins/tempest.conf -O etc/tempest.conf
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
}

def aggregate_parse_failed_smoke(host_ip, results_file, elasticsearch_ip, controller_name='controller01') {
    try {
        sh """
            scp -o StrictHostKeyChecking=no\
            -o ProxyCommand='ssh -W %h:%p root@${host_ip}'\
            -r root@${controller_name}:/root/tempest/output .

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
            -r root@${controller_name}:/root/tempest/subunit .

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
                elastic-upgrade -f \$HOME/output/swift_status.json\
                -n \$HOME/output/nova_status.json -k \$HOME/output/keystone_status.json\
                -p \$HOME/output/persistent_resource.txt -b \$HOME/subunit/smoke/before_upgrade\
                -a \$HOME/subunit/smoke/after_upgrade -g \$HOME/output/nova_api_status.json -w \$HOME/output/swift_api_status.json

                elastic-upgrade -s \$HOME/output/nova_status.json,\$HOME/output/swift_status.json,\$HOME/output/keystone_status.json
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
            cd /root/qa-jenkins-baremetal
            bash rebuild_environment.sh stable/newton
        '''
    """
}

def bash_upgrade_openstack(release='master', retries=2) {
    // ***Requires Params ***
    // release (to upgrade to)
    // retries - number of times to rerun

    String host_ip = get_deploy_node_ip()
    String upgrade_output = ""

    echo "Running upgrade"

    // log upgrade start time
    sh """
        echo "Started: \$(date +%s)" > upgrade_time.txt
        version_start=\$(ssh -o StrictHostKeyChecking=no root@${host_ip} \"cd /opt/openstack-ansible; git status | head -1 | cut -d\\\" \\\" -f3\")
        echo "version_start: \${version_end}" >> upgrade_time.txt
    """

    upgrade_output = run_upgrade_return_results(release, host_ip)

    //take upgrade_output, find out if it's got a failure in it
    echo "Upgrade Results"
    echo "-----------------------"
    echo upgrade_output
    echo "-----------------------"

    String failure_output = parse_upgrade_results_for_failure(upgrade_output)

    if (failure_output.length() > 0) {
        // we have fails, rerun upgrade until it suceeds or to retry limit
        echo "Upgrade failed"
        for (int i = 0; i < retries; i++){
            echo "Rerunning upgrade, retry #" + (i + 1)
            upgrade_output = run_upgrade_return_results(release, host_ip)
            failure_output = parse_upgrade_results_for_failure(upgrade_output)
            if (failure_output.length() == 0){
                echo "Upgrade succeeded"
                // log upgrade end time
                sh """
                    echo "Completed: \$(date +%s)" >> upgrade_time.txt
                    version_end=\$(ssh -o StrictHostKeyChecking=no root@${host_ip} \"cd /opt/openstack-ansible; git status | head -1 | cut -d\\\" \\\" -f3\")
                    echo "version_end: \${version_end}" >> upgrade_time.txt
                    echo "Upgrade: pass" >> upgrade_time.txt
                """
                echo "Echoing Upgrade Results for retry #" + (i + 1)
                echo "------------------------------------"
                echo upgrade_output
                echo "------------------------------------"
                break
            } else if (i == (retries -1)){
                sh """
                    echo "Completed: \$(date +%s)" >> upgrade_time.txt
                    version_end=\$(ssh -o StrictHostKeyChecking=no root@${host_ip} \"cd /opt/openstack-ansible; git status | head -1 | cut -d\\\" \\\" -f3\")
                    echo "version_end: \${version_end}" >> upgrade_time.txt
                    echo "Upgrade: fail" >> upgrade_time.txt
                """
                error "Upgrade failed, exceeded retries"
            }
        }
    }
}

def clear_ssh_host_key(controller_name="controller01") {
    sh """ ssh-keygen -f "/home/ubuntu/.ssh/known_hosts" -R $controller_name"""
}

def run_upgrade_return_results(release="master", host_ip="127.0.0.1"){
    String upgrade_output = ""

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
  /*
  Looking for failed output such as:
  ******************** failure ********************
  The upgrade script has encountered a failure.
  Failed on task "rabbitmq-install.yml -e 'rabbitmq_upgrade=true'"
  Re-run the run-upgrade.sh script, or
  execute the remaining tasks manually:
  openstack-ansible rabbitmq-install.yml -e 'rabbitmq_upgrade=true'
  openstack-ansible etcd-install.yml
  openstack-ansible utility-install.yml
  openstack-ansible rsyslog-install.yml
  openstack-ansible /opt/openstack-ansible/scripts/upgrade-utilities/playbooks/memcached-flush.yml
  openstack-ansible setup-openstack.yml
  ******************** failure ********************
  * Caveat, this only grabs the first failure block and returns it (assumes all controllers will
  either fail the same way, or we're just going to act on any fail the same way)
  */
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

def aggregate_results(host_ip, elasticsearch_ip, controller_name="controller01") {
    // move tempest output
    try {
       sh """
           set -x
           scp -o StrictHostKeyChecking=no\
           -o ProxyCommand='ssh -W %h:%p root@${host_ip}'\
           -r root@${controller_name}:/root/tempest/output .

           scp -o StrictHostKeyChecking=no\
           -r output ubuntu@${elasticsearch_ip}:/home/ubuntu/
       """
    } catch(err) {
       echo "Error moving output directory"
       echo err.message
    }

    // move output from uptime tests
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

    // move upgrade time information
    try {
      sh """
         scp -o StrictHostKeyChecking=no\
         upgrade_time.txt ubuntu@${elasticsearch_ip}:/home/ubuntu/output/
      """
    } catch(err) {
     echo "Error moving upgrade_time.txt"
     echo err.message
    }

    // move tempest subunit information
    try {
       sh """
           set -x
           scp -o StrictHostKeyChecking=no\
           -o ProxyCommand='ssh -W %h:%p root@${host_ip}'\
           -r root@${controller_name}:/root/tempest/subunit .

           scp -o StrictHostKeyChecking=no\
           -r subunit ubuntu@${elasticsearch_ip}:/home/ubuntu/
       """
    } catch(err) {
       echo "No subunit directory found"
       echo err.message
    }
}

// The external code must return it's contents as an object
return this
