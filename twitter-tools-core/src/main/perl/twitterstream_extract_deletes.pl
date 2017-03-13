#!/usr/bin/perl

# Scans a directory containing the output of the stream crawler and
# extracts the deletes

$directory = shift or die "$0 [directory]";

for $f ( `ls $directory` ) {
    chomp($f);
    my $path = "$directory/$f";

    open(DATA, "tar xfO $path --wildcards '*.bz2' | pbzip2 -dc | grep '{\"delete\"' | ");
    while ( my $line = <DATA> ) {
	if ( $line =~ m/{"delete":{"status":{"id":(\d+),/ ) {
	    print "$1\n";
	}
    }
    close(DATA);
}
