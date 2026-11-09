locals {
  api_hostname           = "${var.api_subdomain}.${var.domain_name}"
  availability_zone      = "${var.region}a"
  data_mount_path        = "/var/lib/tyler"
  access_token_parameter = "/tyler/access-token"
  web_ports              = toset(["80", "443"])
  caddy_image            = "caddy:2.11.9-alpine"
  dns_ttl_seconds        = 300

  # The deploy pipeline runs the backend under this name on this network, where Caddy finds it.
  docker_network         = "tyler"
  backend_container_name = "tyler-backend"

  # These match the Dockerfile's non-root user and listening port.
  backend_uid  = 10001
  backend_port = 8080

  # A Windows checkout can have CRLF line endings, which bash on the host cannot run.
  bootstrap_script = replace(templatefile("${path.module}/bootstrap.sh.tftpl", {
    api_hostname           = local.api_hostname
    backend_container_name = local.backend_container_name
    backend_port           = local.backend_port
    backend_uid            = local.backend_uid
    caddy_image            = local.caddy_image
    data_mount_path        = local.data_mount_path
    data_volume_device_id  = replace(aws_ebs_volume.data.id, "-", "")
    docker_network         = local.docker_network
    log_group_name         = aws_cloudwatch_log_group.containers.name
    region                 = var.region
  }), "\r\n", "\n")
}

data "aws_caller_identity" "current" {}

data "aws_vpc" "default" {
  default = true
}

data "aws_subnet" "default" {
  vpc_id            = data.aws_vpc.default.id
  availability_zone = local.availability_zone
  default_for_az    = true
}

data "aws_ssm_parameter" "amazon_linux" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

data "aws_route53_zone" "domain" {
  name = var.domain_name
}

resource "aws_security_group" "web" {
  name        = "tyler-web"
  description = "HTTPS for the backend, and HTTP for certificate issuance"
  vpc_id      = data.aws_vpc.default.id
}

resource "aws_vpc_security_group_ingress_rule" "web" {
  for_each = local.web_ports

  security_group_id = aws_security_group.web.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = tonumber(each.value)
  to_port           = tonumber(each.value)
}

resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.web.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

data "aws_iam_policy_document" "host_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

data "aws_iam_policy_document" "host" {
  statement {
    sid       = "LogInToRegistry"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid = "PullBackendImage"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
    ]
    resources = [aws_ecr_repository.backend.arn]
  }

  statement {
    sid       = "WriteContainerLogs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.containers.arn}:*"]
  }

  statement {
    sid       = "ReadAccessToken"
    actions   = ["ssm:GetParameter"]
    resources = ["arn:aws:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter${local.access_token_parameter}"]
  }
}

resource "aws_iam_role" "host" {
  name               = "tyler-host"
  assume_role_policy = data.aws_iam_policy_document.host_assume.json
}

resource "aws_iam_role_policy" "host" {
  name   = "tyler-host"
  role   = aws_iam_role.host.id
  policy = data.aws_iam_policy_document.host.json
}

# Administration goes through SSM Session Manager, so no SSH port is open.
resource "aws_iam_role_policy_attachment" "session_manager" {
  role       = aws_iam_role.host.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "host" {
  name = "tyler-host"
  role = aws_iam_role.host.name
}

resource "aws_instance" "host" {
  ami                    = data.aws_ssm_parameter.amazon_linux.insecure_value
  instance_type          = var.instance_type
  subnet_id              = data.aws_subnet.default.id
  vpc_security_group_ids = [aws_security_group.web.id]
  iam_instance_profile   = aws_iam_instance_profile.host.name
  user_data              = local.bootstrap_script

  # A hop limit of 1 keeps containers from reading the host role's credentials.
  metadata_options {
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = var.root_volume_gb
    encrypted   = true
  }

  # A new AMI release must not replace the host. Use terraform apply -replace to do that.
  lifecycle {
    ignore_changes = [ami, user_data]
  }

  # Terraform cannot see that the first boot and SSM agent need these permissions.
  depends_on = [aws_iam_role_policy.host, aws_iam_role_policy_attachment.session_manager]

  tags = {
    Name = "tyler-host"
  }
}

resource "aws_eip" "host" {
  domain   = "vpc"
  instance = aws_instance.host.id
}

resource "aws_route53_record" "api" {
  zone_id = data.aws_route53_zone.domain.zone_id
  name    = local.api_hostname
  type    = "A"
  ttl     = local.dns_ttl_seconds
  records = [aws_eip.host.public_ip]
}
