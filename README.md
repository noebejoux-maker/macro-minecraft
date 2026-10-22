# Macro Souris (Fabric 26.2)

Mod **client** : enregistre les mouvements de souris et les clics (gauche = attaquer/casser, droit = utiliser/poser), puis les rejoue.

## Touches (réglables)
Options > Contrôles > Configuration des touches > **Macro Souris**

| Action | Touche par défaut |
|---|---|
| Enregistrer (démarrer / arrêter) | R |
| Stop | V |
| Lecture (démarrer / arrêter) | B |
| Boucle on/off | N |

## Utilisation
1. Appuie sur **R** : l'enregistrement démarre (message dans le chat).
2. Bouge la souris, clique.
3. Appuie sur **V** (ou R) : l'enregistrement s'arrête et la macro est sauvegardée.
4. Appuie sur **B** : la macro est rejouée. **B** ou **V** pour l'arrêter.
5. **N** active la lecture en boucle.

Les mouvements sont relatifs : la macro refait le même geste quelle que soit la direction dans laquelle tu regardes.
La macro est sauvegardée dans `config/macromouse.txt` et rechargée au démarrage.

## Obtenir le .jar (GitHub)
Envoie tout le contenu de ce dossier sur un dépôt GitHub (y compris `.github`), puis **Actions** → **Build mod** → artefact **macromouse-jar**.

## Installer
Java 25 est utilisé par Minecraft 26.x (le launcher le fournit). Installe Fabric pour 26.2 (fabricmc.net), mets `macromouse-1.0.0.jar` et le **Fabric API 0.161.0+26.2** dans `.minecraft/mods`.
