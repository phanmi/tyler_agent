# AWS infrastructure

[Container guide](../../docs/CLOUD_DEPLOYMENT.md)

Terraform for one backend host for a single user: an EC2 instance, an encrypted data volume with daily snapshots, an Elastic IP, a DNS record, an ECR repository, and a CloudWatch log group. Caddy on the host serves HTTPS and forwards to the backend container. There is no SSH; connect with SSM Session Manager.

Terraform state is kept in this directory and ignored by Git. It holds no secrets, but keep a copy: without it, Terraform cannot manage these resources.

## Set up

1. Register a domain in Route 53, which also creates its hosted zone.
2. Install the [AWS CLI](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html) and [Terraform](https://developer.hashicorp.com/terraform/install), and sign in with `aws login`.
3. Create the access token in [AWS CloudShell](https://console.aws.amazon.com/cloudshell/). It stays out of Terraform state.

   ```shell
   aws ssm put-parameter --region us-east-2 --type SecureString \
     --name /tyler/access-token --value "$(openssl rand -hex 32)"
   ```

4. Create the infrastructure from this directory, then review the plan before confirming it:

   ```shell
   terraform init
   terraform apply -var domain_name=example.com
   ```

The data volume has `prevent_destroy` set, so `terraform destroy` stops with an error rather than deleting your data.

Replacing the host with `terraform apply -replace=aws_instance.host` keeps the data volume.
