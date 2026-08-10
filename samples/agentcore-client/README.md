# AgentCore client sample

This sample shows the complete one-dependency Spring Boot setup for an IAM-protected MCP
Streamable HTTP endpoint.

```shell
export MCP_GW_URL=https://your-gateway.example
export AWS_REGION=ap-northeast-2
./gradlew :samples:agentcore-client:run
```

AWS credentials are resolved with the AWS SDK default credentials provider chain.

