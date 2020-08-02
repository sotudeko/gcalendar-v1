#!/bin/bash

gradle clean
gradle build
rm -v gcalendar.zip
cd build/libs
jarfile=`ls -1 gcalendar*.jar`
echo "java -jar ${jarfile}" > run.sh
zip -o ../../gcalendar.zip run.sh ${jarfile}

