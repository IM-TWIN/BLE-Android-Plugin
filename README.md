# BLE Android Plugin

This repository contains an Android plugin that gives the possibility to use BLE in [Godot applications](https://godotengine.org).
The module to build and use is the one contained in the directory `bleframework/`.


## Setup

### Building the plugin in Android Studio

After downloading this repository, open the relative folder with Android Studio, and then build the module **bleframework**. Once the operation is done, you will find the **Android archive library** (*aar* archive file) in *bleframework/build/outputs/aar/*.

### Android Environment in Godot

In order to build an Android application, you need to set up the Godot environment as explained [here](https://docs.godotengine.org/it/stable/getting_started/workflow/export/android_custom_build.html).

### Loading the plugin

Move the plugin configuration file (`bleframework/bleframework.gdap`) and the local binary (`bleframework/build/outputs/aar/bleframework-debug.aar`) that you generated to the Godot project's `res://android/plugins` directory (if they do not exist, create them).
The Godot editor will automatically parse the **.gdap** file and show our plugin **"BLE Plugin"** in the Android export presets window under the **Plugins** section.
Once you enable it, you can use it in your code as explained in the next section.

## How to use the plugin

The plugin makes available the main functionalities for an Android device (central) to connect and communicate with a peripheral.
The main functionalities are:
- start scanning / stop scanning
- set filters for the scanning
- connect/disconnect to/from a device
- enable/disable characteristic's notifications (and receive them)
- read/write a characteristic

To use the plugin in Godot, in your script you have to retrieve the correspondent singleton, as shown below.
``` gdnative
if Engine.has_singleton("BLEPlugin"):
    var ble = Engine.get_singleton("BLEPlugin") 
    ble.initialize()
```

The variable `ble` is the object that contains all the methods of the Java class, through which you can access the BLE operations.

Since the Android BLE library uses many callbacks in Java, in those cases they carry meaningful information a signal is emitted so that it can also be seen in Godot. To receive these signals and thus perform some actions, the signal has to be connected to a function in the Godot script as shown below.
```gdnative
ble.connect("device_found", self, "_on_dev_found")
```
In this way, when the signal "device_found" is emitted, the function "_on_dev_found" is instantly executed.

## Authors
- [Francesca Romana Mattei](https://github.com/francescaromana) 
- [Massimiliano Schembri](https://github.com/schembrimax) 

## Funding
This software is part of a project that has received funding from the European Union’s Horizon 2020 research and innovation programme under grant agreement No 952095 (www.im-twin.eu) and No 945887 (www.plusme-h2020.eu)

[<img src="/images/plusme_logo.png" width="100">](https://www.plusme-h2020.eu) [<img src="/images/imtwin-logo_2020.png" width="125">](https://im-twin.eu)

 
