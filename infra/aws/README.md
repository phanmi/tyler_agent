# AWS infrastructure

[Container guide](../../docs/CLOUD_DEPLOYMENT.md)

Terraform for one backend host for a single user: an EC2 instance, an encrypted data volume with daily snapshots, an Elastic IP, a DNS record, an ECR repository, and a CloudWatch log group. Caddy on the host serves HTTPS and forwards to the backend container. There is no SSH; connect with SSM Session Manager.

Terraform state is kept in this directory and ignored by Git. It holds no secrets, but keep a copy: without it, Terraform cannot manage these resources.

## Set up

1. Make sure the region still has its default VPC and a default subnet in zone `a`, which the host uses. If the default VPC was deleted, restore it and its subnets with `aws ec2 create-default-vpc`. If only the subnet was deleted, restore it with `aws ec2 create-default-subnet --availability-zone <region>a`, for example `us-east-2a`.
2. Get a domain with exactly one Route 53 hosted zone. Registering through Route 53 creates the zone. For a domain from another registrar, create the zone with `aws route53 create-hosted-zone` and set the domain's nameservers to the four it returns.
3. Install the [AWS CLI](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html) and [Terraform](https://developer.hashicorp.com/terraform/install), and sign in with `aws login`.
4. Create the access token in [AWS CloudShell](https://console.aws.amazon.com/cloudshell/), in the region you deploy to (`us-east-2` by default). It stays out of Terraform state.

   ```shell
   aws ssm put-parameter --region us-east-2 --type SecureString \
     --name /tyler/access-token --value "$(openssl rand -hex 32)"
   ```

5. Create the infrastructure from this directory, then review the plan before confirming it:

   ```shell
   terraform init
   terraform apply -var domain_name=example.com
   ```

The data volume has `prevent_destroy` set, so `terraform destroy` stops with an error rather than deleting your data.

Replacing the host with `terraform apply -replace=aws_instance.host` keeps the data volume.
