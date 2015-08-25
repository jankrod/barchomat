#!/bin/sh
# Uncompress a clash csv rule file
# see https://github.com/clanner/cocdp/wiki/Csv-Files

mkdir extract
unzip *.apk -d extract

mkdir logic

for f in "$@"
do
    if [ -f ${f} ] ; then
        (
            dd if="${f}" bs=1 count=9
            dd if=/dev/zero bs=1 count=4
            dd if="${f}" bs=1 skip=9
        ) | unlzma -dc > logic/$(basename "${f}")
    fi
done

rm -r extract