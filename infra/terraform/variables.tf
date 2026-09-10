variable "region" {
  type    = string
  default = "us-east-1"
}
variable "vpc_id" { type = string }
variable "vpc_cidr" { type = string }
variable "client_cidr" {
  type        = string
  description = "Private network CIDR allowed to reach the internal HTTPS endpoint."
}
variable "private_subnet_ids" {
  type = list(string)
  validation {
    condition     = length(var.private_subnet_ids) >= 2
    error_message = "Supply private subnets in at least two availability zones."
  }
}
variable "certificate_arn" { type = string }
variable "image_uri" {
  type        = string
  description = "Published image URI, preferably pinned by sha256 digest."
}
variable "database_url" {
  type        = string
  description = "Existing private PostgreSQL JDBC URL; require SSL in production."
}
variable "database_user" { type = string }
variable "database_password_secret_arn" { type = string }
variable "api_key_secret_arn" { type = string }
