-- A guest and an authenticated inbound may share one provisioning agent.
ALTER TABLE nodes DROP CONSTRAINT IF EXISTS uq_nodes_api_endpoint;
ALTER TABLE nodes ADD CONSTRAINT uq_nodes_agent_inbound UNIQUE (api_host, api_port, inbound_tag);
