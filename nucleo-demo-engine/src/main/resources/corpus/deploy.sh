#!/bin/sh
# Deploys the dealer portal to the Kiel host. Run from the release directory.
set -e
VERSION="$1"
if [ -z "$VERSION" ]; then
  echo "usage: deploy.sh <version>" >&2
  exit 1
fi
scp "portal-$VERSION.jar" portal@kiel-01.halcyon.internal:/opt/portal/
ssh portal@kiel-01.halcyon.internal "cd /opt/portal && ln -sfn portal-$VERSION.jar portal.jar && systemctl --user restart portal"
echo "portal $VERSION deployed"
