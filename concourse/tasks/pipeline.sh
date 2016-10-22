#!/bin/bash

export SCRIPTS_OUTPUT_FOLDER=${ROOT_FOLDER}/${REPO_RESOURCE}/ciscripts
echo "Scripts will be copied to [${SCRIPTS_OUTPUT_FOLDER}]"

echo "Copying pipelines scripts"
cd ${ROOT_FOLDER}/${REPO_RESOURCE}
mkdir ${SCRIPTS_OUTPUT_FOLDER}
cp ${ROOT_FOLDER}/${TOOLS_RESOURCE}/common/src/main/bash/* ${SCRIPTS_OUTPUT_FOLDER}/

echo "Retrieving version"
cp ${ROOT_FOLDER}/${VERSION_RESOURCE}/version ${SCRIPTS_OUTPUT_FOLDER}/
export PIPELINE_VERSION=$( cat ${SCRIPTS_OUTPUT_FOLDER}/${VERSION_RESOURCE} )
echo "Retrieved version is [${PIPELINE_VERSION}]"

M2_LOCAL=${ROOT_FOLDER}/${M2_REPO}
echo "Changing the maven local to [${M2_LOCAL}]"
export MAVEN_ARGS="-Dmaven.repo.local=${M2_LOCAL}"

echo "Sourcing file with pipeline functions"
source ${SCRIPTS_OUTPUT_FOLDER}/pipeline.sh

export TERM=dumb

cd ${ROOT_FOLDER}


# TODO: Move to image
sudo apt-get -y update
sudo apt-get -y install software-properties-common

sudo apt-get -y update
sudo apt-get -y install \
  git \
  build-essential \
  curl \
  wget \
  libssl-dev \
  libxml2-dev \
  libsqlite3-dev \
  libxslt1-dev \
  libpq-dev \
  libmysqlclient-dev \
  bsdtar \
  zip \
  unzip \
  python \
  gem \
  xvfb \
  x11-xkb-utils \
  xfonts-100dpi \
  xfonts-75dpi \
  xfonts-scalable \
  xfonts-cyrillic \
  x11-apps \
  libqtwebkit-dev \
  qt4-qmake