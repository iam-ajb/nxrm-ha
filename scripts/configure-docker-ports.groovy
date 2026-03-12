// Script to configure HTTP connector ports for Docker repositories in Nexus 3
// Uses the internal Provisioning API

import org.sonatype.nexus.repository.Repository
import org.sonatype.nexus.repository.manager.RepositoryManager
import org.sonatype.nexus.repository.config.Configuration

import javax.inject.Inject

def repositoryManager = container.lookup(RepositoryManager.class.getName())

// Define the explicit mapping to match values.yaml and docker-routes.yaml
def portMap = [
    'dkr-public': 5000,
    'dkr-4-test': 5001,
    'dkr-4-production': 5002,
    'dkr-prod-public': 5003,
    'dkr-test-public': 5004,
    'dkr-sunrise-common': 5005,
    'mvn-public-releases': 5006,
    'dkr-nts-public': 5007,
    'docker-nts-private': 5008
]

log.info("Starting Docker repository connector port configuration...")

repositoryManager.browse().each { Repository repo ->
    String repoName = repo.getName()
    if (repo.getFormat().getValue() == "docker" && portMap.containsKey(repoName)) {
        int targetPort = portMap[repoName]
        Configuration config = repo.getConfiguration()
        
        // Check current port config
        def httpPort = config.attributes('docker').get('httpPort')
        
        if (httpPort != targetPort) {
            log.info("Configuring HTTP port ${targetPort} for Docker repository: ${repoName}")
            
            // Set the HTTP port and enable V1 API
            config.attributes('docker').set('httpPort', targetPort)
            config.attributes('docker').set('v1Enabled', true)
            
            // Apply the configuration update
            repositoryManager.update(config)
            
            log.info("Successfully updated repository: ${repoName} to use port ${targetPort}")
        } else {
            log.info("Docker repository ${repoName} is already correctly configured on port ${targetPort}. Skipping.")
        }
    }
}

log.info("Docker repository connector port configuration complete.")
return "Completed Docker port configuration."
