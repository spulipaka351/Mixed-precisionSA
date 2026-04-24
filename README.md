# Mixed-precisionSA

A high-performance Systolic Array (SA) designed in Chisel that supports dynamically reconfigurable **INT8** and **FP16** computations. This project demonstrates significant area efficiency by sharing hardware resources between integer and floating-point datapaths.

---

## 🚀 Getting Started

### Prerequisites
* **JDK 8 or 11+**
* **sbt** (Scala Build Tool)
* **Verilator** (for simulation)
* **Yosys** (for synthesis and area reporting)

---

## 🛠️ Development Workflow

### 1. Compile Chisel 
Translate your Scala hardware description into FIRRTL and check for syntax errors.
```console
sbt compile

# To test the Mixed-Precision PE
sbt "testOnly Mixed.MixedPETest"

# To test the full Systolic Array
sbt "testOnly Mixed.TopMixedSATest"
# Generates .v files in the /verilog_output directory
sbt "runMain Mixed.ElaborateTopSA"
```

### 2.Yosys

```console 
 yosys
 ```

```console  
yosys > read_verilog Top.v 
```


```console  
yosys > synth -top Top
```

```console  
yosys > tee -o report.txt stat
```