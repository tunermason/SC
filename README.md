# Meshenger

True P2P voice- and video phone calls without the need for accounts or access to the Internet. There is no discovery mechanism, no meshing and no servers. Just scan each others QR-Code that will contain the contacts IP address. This works in many local networks such as community mesh networks, company networks or at home.

Features:

- voice and video calls
- no accounts or registration
- encrypted communication
- database backup and encryption
- add custom addresses to reach contacts
- VPN Network to access contacts over WAN
  - Currently uses preconfigured vpn profiles for testing. Plans to also add custom configurations. Currently the Android API only allows TUN interfaces and not TAP interfaces.

Known Bugs:
- Crashes when interface selected is no longer avaiable. (VPN is disconnected at startup)
