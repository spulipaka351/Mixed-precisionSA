#!/bin/bash

TOP="TopMixedSA"
SRCDIR="mixed_opt"
SRC="TopMixedSA.v"
OUTDIR="mixed_opt_report"

mkdir -p "$OUTDIR"

yosys -p "
read_verilog -sv $SRCDIR/$SRC;
synth -top $TOP;
stat;
tee -o $OUTDIR/synth_report.txt stat;
"
