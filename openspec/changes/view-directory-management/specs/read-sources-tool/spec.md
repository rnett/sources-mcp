## REMOVED Requirements

### Requirement: read_sources MCP tool

The `read_sources` MCP tool is out of scope for this change. The service layer (`SourcesViewService`) provides the functionality; MCP tool implementation is deferred.

#### Scenario: MCP tool deferred

- **WHEN** this change is reviewed
- **THEN** no MCP tool is implemented; only the service layer exists
