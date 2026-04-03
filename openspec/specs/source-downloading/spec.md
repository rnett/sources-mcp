# Capability: Source Downloading

## Purpose

[TBD] This capability handles the downloading and extraction of dependency source code using the OSS Review Toolkit (ORT) and manages its storage in the Content-Addressable Storage (CAS).

## Requirements

### Requirement: Download dependency sources using ORT

The system MUST download the source code for a given dependency using the OSS Review Toolkit (ORT) if it is not already available in the CAS.

#### Scenario: Source not in CAS

- **WHEN** a download is requested for a dependency not present in the CAS
- **THEN** the system uses ORT to fetch the sources and stores them in the CAS

#### Scenario: Source already in CAS

- **WHEN** a download is requested for a dependency already present in the CAS
- **THEN** the system returns the path to the existing CAS entry without re-downloading

### Requirement: Atomic extraction to CAS

The system MUST extract downloaded sources to a temporary directory and atomically move them to the final CAS destination to prevent readers from observing partial states.

#### Scenario: Successful extraction

- **WHEN** sources are successfully downloaded and extracted to a temporary directory
- **THEN** the temporary directory is atomically moved to the final CAS path

#### Scenario: Atomic move fails

- **WHEN** the atomic move to the CAS directory fails
- **THEN** the system MUST retry the move operation or fail gracefully, ensuring no corrupted state is left in the CAS

### Requirement: Advisory locking for concurrent downloads

The system MUST use an advisory file lock specific to the dependency hash to ensure that concurrent requests for the same dependency do not result in redundant downloads or extraction conflicts.

#### Scenario: Worker acquires lock

- **WHEN** a worker successfully acquires the advisory lock for a dependency hash
- **THEN** it proceeds with downloading and extracting the sources

#### Scenario: Worker is denied lock

- **WHEN** a worker fails to acquire the advisory lock because another worker holds it
- **THEN** it MUST suspend execution and poll until the final CAS directory is created by the lock holder
