module.exports = {
    talkTo: function(player, npc){
        // TODO: Dialogues
        this.trade(player, npc);
    },

    trade: function(player, npc){
        vendor("Flynn's Mace Market");
    }
}