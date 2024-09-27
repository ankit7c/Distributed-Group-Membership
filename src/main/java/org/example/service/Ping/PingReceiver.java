package org.example.service.Ping;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.entities.FDProperties;
import org.example.entities.Member;
import org.example.entities.MembershipList;
import org.example.entities.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PingReceiver extends Thread{
    private static final Logger logger = LoggerFactory.getLogger(PingReceiver.class);

    private DatagramSocket serverSocket;
    private boolean running;
    private ObjectMapper objectMapper;
//    private byte[] buf = new byte[16384];

    public PingReceiver() throws SocketException {
        objectMapper = new ObjectMapper();
        serverSocket = new DatagramSocket((int)FDProperties.getFDProperties().get("machinePort"));
    }

    public void run() {
        running = true;
        System.out.println("Listener service for Dissemination Component started");
        while (running) {
            byte[] buf = new byte[16384];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            try {
                serverSocket.receive(packet);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            System.out.println("Ping received");
            InetAddress address = packet.getAddress();
            int port = packet.getPort();
//            packet = new DatagramPacket(buf, buf.length, address, port);
            String received = new String(
                    packet.getData(), 0, packet.getLength());

            System.out.println(received);
            Message message = Message.process(address, String.valueOf(port), received);

            //TODO based on the message received take the action
            //TODO add the introducer under this class and set a flag of if introducer then only execute the below code
            //TODO same will be for suspicion mode.
            //Add a switch case
            switch (message.getMessageName()){
                case "introduction":
                    try {
                        logger.info("Introduction successful message received");
                        MembershipList.addMember(
                                new Member((String) message.getMessageContent().get("senderName"),
                                        (String) message.getMessageContent().get("senderIp"),
                                        ((String) message.getMessageContent().get("senderPort")),
                                        (String) message.getMessageContent().get("version"),
                                        "alive",
                                        Member.getLocalDateTime())
                        );
                        MembershipList.printMembers();
                        System.out.println("Receiving membership list");
                        buf = new byte[16384];
                        packet = new DatagramPacket(buf, buf.length, address, port);
                        serverSocket.receive(packet);
                        String json = new String(
                                packet.getData(), 0, packet.getLength());
                        System.out.println(json);
                        ConcurrentHashMap<String,Object> temp = objectMapper.readValue(json, ConcurrentHashMap.class);
                        temp.forEach((k,v) -> {
                            try {
                                String t = objectMapper.writeValueAsString(v);
                                Map<String, String> map = objectMapper.readValue(t, Map.class);
                                System.out.println(map);
                                if(!map.get("name").equals(FDProperties.getFDProperties().get("machineName"))) {
                                    MembershipList.addMember(
                                            new Member(map.get("name"), map.get("ipAddress"), map.get("port"), map.get("versionNo"), map.get("status"), map.get("dateTime"))
                                    );
                                }
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    MembershipList.printMembers();
                    break;
                case "alive":
                    if(message.getMessageContent().get("isIntroducing").equals("true") && (Boolean) FDProperties.getFDProperties().get("isIntroducer")){
                        logger.info("A node wants to join a group with ip address " + packet.getAddress() + ":" + packet.getPort()
                                + " with version " + message);
                        String result = "";
                        //if a node sends an alive message to join the group then multicast that message to everyone
                        try {
                            PingSender pingSender = new PingSender();
                            result = pingSender.multicast(message);
                            //add this member to its own list
                            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
                            LocalDateTime dateTime = LocalDateTime.of(1986, Month.APRIL, 8, 12, 30);
                            String formattedDateTime = dateTime.format(formatter);
                            MembershipList.addMember(
                                    new Member((String) message.getMessageContent().get("senderName"),
                                            (String) message.getMessageContent().get("senderIp"),
                                            ((String) message.getMessageContent().get("senderPort")),
                                            (String) message.getMessageContent().get("version"),
                                            "alive",
                                            Member.getLocalDateTime())
                            );
                            MembershipList.printMembers();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        if(result.equals("Successful")) {
                            logger.info("Member Introduced in the Group");
                            PingSender pingSender = new PingSender();
                            Map<String, Object> messageContent = new HashMap<>();
                            messageContent.put("messageName", "introduction");
                            messageContent.put("senderName", (String) FDProperties.getFDProperties().get("machineName"));
                            messageContent.put("senderIp", (String) FDProperties.getFDProperties().get("machineIp"));
                            messageContent.put("senderPort", String.valueOf(FDProperties.getFDProperties().get("machinePort")));
                            messageContent.put("msgId", FDProperties.generateRandomMessageId());
                            messageContent.put("isIntroducing", "false");
                            try {
                                Message introduceBackMessage = new Message("alive",
                                        (String) message.getMessageContent().get("senderIp"),
                                        ((String) message.getMessageContent().get("senderPort")),
                                        messageContent);
                                pingSender.sendMessage(introduceBackMessage);
                                ObjectMapper objectMapper = new ObjectMapper();
                                String json = objectMapper.writeValueAsString(MembershipList.members);
//                                messageContent.put("members", json);
                                System.out.println("Sending Member List" + json);
                                pingSender.sendMessage(introduceBackMessage, json);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }else {
                        logger.info("Alive message received");
                        if(message.getMessageContent().get("senderName").equals(FDProperties.getFDProperties().get("machineName")))
                            break;
                        MembershipList.addMember(
                                new Member((String) message.getMessageContent().get("senderName"),
                                        (String) message.getMessageContent().get("senderIp"),
                                        ((String) message.getMessageContent().get("senderPort")),
                                        (String) message.getMessageContent().get("version"),
                                        "alive",
                                        Member.getLocalDateTime())
                        );
                        MembershipList.printMembers();
                    }
                    break;
                case "ping":
                    System.out.println("Ping received");
                    Map<String, Object> messageContent = new HashMap<>();
                    messageContent.put("messageName", "pingAck");
                    messageContent.put("senderName", (String) FDProperties.getFDProperties().get("machineName"));
                    messageContent.put("senderIp", (String) FDProperties.getFDProperties().get("machineIp"));
                    messageContent.put("senderPort", String.valueOf(FDProperties.getFDProperties().get("machinePort")));
                    messageContent.put("msgId", FDProperties.generateRandomMessageId());
                    System.out.println("Sending Ping Ack");
                    try {
                        buf = new byte[16384];
                        buf = objectMapper.writeValueAsString(messageContent).getBytes();
                        DatagramPacket pingAckPacket
                                = new DatagramPacket(buf, buf.length, address, port);
                        serverSocket.send(pingAckPacket);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    break;
                case "pingReq":
                    System.out.println("Ping Required received");
                    Map<String, Object> pingReqContent = new HashMap<>();
                    pingReqContent.put("messageName", "pingReqJ");
                    pingReqContent.put("senderName", FDProperties.getFDProperties().get("machineName"));
                    pingReqContent.put("senderIp", FDProperties.getFDProperties().get("machineIp"));
                    pingReqContent.put("senderPort", String.valueOf(FDProperties.getFDProperties().get("machinePort")));
                    pingReqContent.put("msgId", message.getMessageContent().get("msgId"));
                    pingReqContent.put("originalSenderIp", address);
                    pingReqContent.put("originalSenderPort", String.valueOf(port));
                    try {
                        System.out.println("Sending Ping Ack");
                        Message pingReqMessage = new Message("ping", (String) message.getMessageContent().get("targetSenderIp"), String.valueOf(Integer.parseInt((String) message.getMessageContent().get("targetSenderPort"))), pingReqContent);
                        PingSender pingSender = new PingSender();
                        pingSender.sendMessage(pingReqMessage);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    break;
                case "pingReqJ":
                    System.out.println("Ping Required J received");
                    Map<String, Object> pingREqJContent = message.getMessageContent();
                    pingREqJContent.put("messageName", "pingReqJAck");
                    pingREqJContent.put("senderName", FDProperties.getFDProperties().get("machineName"));
                    pingREqJContent.put("senderIp", FDProperties.getFDProperties().get("machineIp"));
                    pingREqJContent.put("senderPort", String.valueOf(FDProperties.getFDProperties().get("machinePort")));
                    System.out.println("Sending Ping Required J Ack");
                    try {
                        buf = new byte[16384];
                        buf = objectMapper.writeValueAsString(pingREqJContent).getBytes();
                        DatagramPacket pingAckPacket
                                = new DatagramPacket(buf, buf.length, address, port);
                        serverSocket.send(pingAckPacket);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    break;
                case "pingReqJAck":
                    System.out.println("Ping Required J Ack received");
                    Map<String, Object> pingREqJAckContent = message.getMessageContent();
                    pingREqJAckContent.put("messageName", "pingReqAck");
                    pingREqJAckContent.put("senderName", FDProperties.getFDProperties().get("machineName"));
                    pingREqJAckContent.put("senderIp", FDProperties.getFDProperties().get("machineIp"));
                    pingREqJAckContent.put("senderPort", String.valueOf(FDProperties.getFDProperties().get("machinePort")));
                    System.out.println("Sending Ping Required J Ack");
                    try {
                        Message pingReqMessage = new Message("pingReqAck", (String) message.getMessageContent().get("originalSenderIp"),
                                ((String) message.getMessageContent().get("originalSenderPort")), pingREqJAckContent);
                        PingSender pingSender = new PingSender();
                        pingSender.sendMessage(pingReqMessage);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    break;
                case "failed" :
                    logger.info("Failed message received");
                    MembershipList.removeMember((String) message.getMessageContent().get("memberName"));
                    break;
                case "suspect" :
                    logger.info("Suspect message received");
                    //TODO add a piece of code to set the status of a member to suspect
                    //TODO add a piece of code which will send alive multicast if the suspect node is itself
                    break;
                case "confirm" :
                    logger.info("Confirm message received");
                    //TODO add a piece of code that will remove the member from the list
//                    MembershipList.removeMember(address);
                    break;
                default:
            }
        }
        serverSocket.close();
    }

    public static StringBuilder data(byte[] a)
    {
        if (a == null)
            return null;
        StringBuilder ret = new StringBuilder();
        int i = 0;
        while (a[i] != 0)
        {
            ret.append((char) a[i]);
            i++;
        }
        return ret;
    }
}
