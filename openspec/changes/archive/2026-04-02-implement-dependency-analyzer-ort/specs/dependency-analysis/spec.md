## ADDED Requirements

### Requirement: Analyze Project Root

The system SHALL accept a project root path and perform a dependency analysis using ORT.

#### Scenario: Successful Analysis

- **WHEN** a valid project root (e.g., a directory with a `build.gradle.kts` or `package.json`) is provided
- **THEN** the system SHALL return a path to a generated ORT result file

### Requirement: Support Multiple Ecosystems

The system SHALL support analysis for common package managers, specifically Gradle and NPM.

#### Scenario: Analyze Gradle Project

- **WHEN** a Gradle project root is provided to the analyzer
- **THEN** the analyzer SHALL successfully identify the project's dependencies and include them in the result

#### Scenario: Analyze NPM Project

- **WHEN** an NPM project root is provided to the analyzer
- **THEN** the analyzer SHALL successfully identify the project's dependencies and include them in the result

### Requirement: Handle Analysis Failures

The system SHALL throw an informative exception if the dependency analysis fails to complete.

#### Scenario: Invalid Project Root

- **WHEN** an empty directory or a non-existent path is provided as a project root
- **THEN** the system SHALL throw an exception indicating the failure
