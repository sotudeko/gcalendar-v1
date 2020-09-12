#!/bin/bash
rm -v gcalendar.zip
gradle clean build nexusIQScan copyDependencies gcRunfile gcBundle


