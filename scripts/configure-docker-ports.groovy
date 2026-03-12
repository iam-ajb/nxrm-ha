// Script to configure HTTP connector ports for Docker repositories in Nexus 3
// Uses the internal Provisioning API

import org.sonatype.nexus.repository.Repository
import org.sonatype.nexus.repository.manager.RepositoryManager
import org.sonatype.nexus.repository.config.Configuration

import javax.inject.Inject

def repositoryManager = container.lookup(RepositoryManager.class.getName())

// Define a safe starting port range for Docker repositories
int startPort = 5000

log.info("Starting Docker repository connector port configuration...")

repositoryManager.browse().each { Repository repo ->
    if (repo.getFormat().getValue() == "docker") {
        Configuration config = repo.getConfiguration()
        
        // Check if there's already an HTTP or HTTPS port configured
        def httpPort = config.attributes('docker').get('httpPort')
        def httpsPort = config.attributes('docker').get('httpsPort')
        
        if (!httpPort && !httpsPort) {
            log.info("Configuring HTTP port ${startPort} for Docker repository: ${repo.getName()}")
            
            // Set the HTTP port
            config.attributes('docker').set('httpPort', startPort)
            
            // Also ensure V1 API is enabled as requested by user
            config.attributes('docker').set('v1Enabled', true)
            
            // Apply the configuration update
            repositoryManager.update(config)
            
            log.info("Successfully updated repository: ${repo.getName()} to use port ${startPort}")
            
            // Increment the port for the next repository
            startPort++
        } else {
            log.info("Docker repository ${repo.getName()} already has a port configured (HTTP: ${httpPort}, HTTPS: ${httpsPort}). Skipping.")
        }
    }
}

log.info("Docker repository connector port configuration complete.")
return "Completed Docker port configuration."
