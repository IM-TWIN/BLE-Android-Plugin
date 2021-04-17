# BLE Android Plugin

This repository contains an Android plugin that gives the possibility to use BLE in Godot.
The module to build and use is the one contained in the directory *bleframework*.

## Environment Setup in Godot

## How to use the plugin

The plugin makes available the main functionalities for a Android device (central) to connect and communicate with a peripheral.
The main functionalities are:
- start scanning / stop scanning
- set filters for the scanning
- connect/disconnect to/from a device
- enable/disable characterisitc's notifications (and receive them)
- read/write a characteristic

In order to use the plugin in Godot, in your script you have to retrieve the correspondent singleton, as shown below.
`
if Engine.has_singleton("BLEPlugin"):
    var ble = Engine.get_singleton("BLEPlugin") 
    ble.initialize()
`

The variable `ble` is the object that contains all the methods of the Java class, through which you can access the BLE operations.

Since BLE uses many callbacks in Java, in those cases that they carry meaningful information a signal is emitted so that it can also be seen in Godot. In order to receive these signals and thus perform some actions, the signal has to be connected to a function in the Godot script as shown below.
`
ble.connect("device_found", self, "_on_dev_found")
`
In this way, when the signal "device_found" is emitted, the function "_on_dev_found" is instantly executed.